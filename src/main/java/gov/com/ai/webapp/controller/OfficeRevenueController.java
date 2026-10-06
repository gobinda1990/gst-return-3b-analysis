package gov.com.ai.webapp.controller;

import gov.com.ai.webapp.model.revenue.*;
import gov.com.ai.webapp.service.revenue.OfficeRevenueDashboardService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * GSTR-3B office revenue dashboard API (read-only + CSV export).
 *
 * <p>Validation errors are mapped to HTTP 400 by {@link OfficeRevenueExceptionHandler}.
 * The monthly batch endpoint lives with the batch code; after a batch run it must call
 * {@code OfficeRevenueDashboardService#evictDashboardCaches()}.
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/gst/return-3b/office-revenue")
public class OfficeRevenueController {

	/** MMYYYY, month 01-12, year 2000-2099. */
	private static final String PERIOD = "(0[1-9]|1[0-2])20\\d{2}";
	private static final String PERIOD_MSG = "must be in MMYYYY format, e.g. 032025";

	/** Must list the same values as OfficeRevenueDashboardService.GROWTH_STATUSES. Empty = no filter. */
	private static final String GROWTH = "(?i)(HIGH|MEDIUM|LOW|STABLE|DECLINING|NO_BASE)?";
	private static final String GROWTH_MSG = "must be HIGH, MEDIUM, LOW, STABLE, DECLINING or NO_BASE";

	private static final int SUMMARY_PAGE = 0;
	private static final int SUMMARY_SIZE = 25;
	private static final int EXPORT_PAGE = 0;
	private static final int EXPORT_SIZE = 100;
	private static final long SLOW_MS = 2_000;
	private static final int MAX_LOG_VALUE = 300;
	private static final byte[] UTF8_BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

	private static final CacheControl NO_STORE = CacheControl.noStore();
	private static final CacheControl LOOKUP_CACHE = CacheControl.maxAge(Duration.ofMinutes(1)).cachePrivate();

	private final OfficeRevenueDashboardService dashboard;

	// ------------------------------------------------------------------ reads

	@GetMapping("/summary")
	public ResponseEntity<OfficeRevenueDashboardResponse> summary(
			@RequestParam @Pattern(regexp = PERIOD, message = PERIOD_MSG) String retPeriod,
			@RequestParam(required = false) @Size(max = 100) String office,
			@RequestParam(required = false) @Pattern(regexp = GROWTH, message = GROWTH_MSG) String growthStatus,
			@RequestParam(required = false) @Size(max = 100) String search) {
		RevenueDashboardFilter f = filter(retPeriod, office, growthStatus, search, SUMMARY_PAGE, SUMMARY_SIZE);
		OfficeRevenueDashboardResponse body = timed("summary", f, () -> dashboard.summary(f));
		return ResponseEntity.ok().cacheControl(NO_STORE).body(body);
	}

	@GetMapping("/list")
	public ResponseEntity<PageResponse<OfficeRevenueRowResponse>> list(
			@RequestParam @Pattern(regexp = PERIOD, message = PERIOD_MSG) String retPeriod,
			@RequestParam(required = false) @Size(max = 100) String office,
			@RequestParam(required = false) @Pattern(regexp = GROWTH, message = GROWTH_MSG) String growthStatus,
			@RequestParam(required = false) @Size(max = 100) String search,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
		RevenueDashboardFilter f = filter(retPeriod, office, growthStatus, search, page, size);
		PageResponse<OfficeRevenueRowResponse> body = timed("list", f, () -> dashboard.page(f));
		return ResponseEntity.ok().cacheControl(NO_STORE).body(body);
	}

	@GetMapping("/trend")
	public ResponseEntity<List<OfficeRevenueTrendResponse>> trend(
			@RequestParam @Pattern(regexp = PERIOD, message = PERIOD_MSG) String retPeriod,
			@RequestParam(required = false) @Size(max = 100) String office,
			@RequestParam(defaultValue = "12") @Min(1) @Max(36) int months) {
		String o = blankToNull(office);
		String ctx = "period=" + retPeriod + " office=" + safe(o) + " months=" + months;
		List<OfficeRevenueTrendResponse> body = timed("trend", ctx, () -> dashboard.trend(retPeriod, o, months));
		return ResponseEntity.ok().cacheControl(LOOKUP_CACHE).body(body);
	}

	@GetMapping("/periods")
	public ResponseEntity<List<OptionDto>> periods() {
		List<OptionDto> body = timed("periods", "", dashboard::periods);
		return ResponseEntity.ok().cacheControl(LOOKUP_CACHE).body(body);
	}

	@GetMapping("/offices")
	public ResponseEntity<List<OptionDto>> offices(
			@RequestParam @Pattern(regexp = PERIOD, message = PERIOD_MSG) String retPeriod) {
		List<OptionDto> body = timed("offices", "period=" + retPeriod, () -> dashboard.offices(retPeriod));
		return ResponseEntity.ok().cacheControl(LOOKUP_CACHE).body(body);
	}

	// ----------------------------------------------------------------- export

	/**
	 * Streams the CSV. The filter is validated BEFORE the stream starts: once streaming begins the 200 status is
	 * already sent and an error could only produce a truncated file. No {@code produces} restriction, so clients
	 * sending another Accept header still get the file instead of a 406.
	 */
	@GetMapping("/export")
	public ResponseEntity<StreamingResponseBody> export(
			@RequestParam @Pattern(regexp = PERIOD, message = PERIOD_MSG) String retPeriod,
			@RequestParam(required = false) @Size(max = 100) String office,
			@RequestParam(required = false) @Pattern(regexp = GROWTH, message = GROWTH_MSG) String growthStatus,
			@RequestParam(required = false) @Size(max = 100) String search) {

		final RevenueDashboardFilter f = dashboard
				.validate(filter(retPeriod, office, growthStatus, search, EXPORT_PAGE, EXPORT_SIZE));
		final String ctx = describe(f);

		StreamingResponseBody body = os -> {
			long t0 = System.nanoTime();
			log.info("Office revenue export started {}", ctx);
			try {
				os.write(UTF8_BOM);
				BufferedWriter w = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), 65_536);
				dashboard.export(f, w);
				w.flush();
				log.info("Office revenue export finished {} elapsedMs={}", ctx, ms(t0));
			} catch (IOException e) {
				// usually the client closed the connection
				log.warn("Office revenue export interrupted {} elapsedMs={} cause={}", ctx, ms(t0), e.toString());
				throw e;
			} catch (RuntimeException e) {
				// headers are already sent, so the client receives a truncated file
				log.error("Office revenue export failed mid-stream {} elapsedMs={}", ctx, ms(t0), e);
				throw e;
			}
		};

		return ResponseEntity.ok().cacheControl(NO_STORE)
				.header(HttpHeaders.CONTENT_DISPOSITION,
						"attachment; filename=\"gst-3b-office-revenue-" + f.retPeriod() + ".csv\"")
				.contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(body);
	}

	// ---------------------------------------------------------------- helpers

	private static RevenueDashboardFilter filter(String retPeriod, String office, String growthStatus, String search,
			int page, int size) {
		return new RevenueDashboardFilter(retPeriod, blankToNull(office), blankToNull(growthStatus),
				blankToNull(search), page, size);
	}

	private <T> T timed(String op, RevenueDashboardFilter f, Supplier<T> call) {
		return timed(op, describe(f), call);
	}

	private <T> T timed(String op, String ctx, Supplier<T> call) {
		long t0 = System.nanoTime();
		T result = call.get();
		long elapsed = ms(t0);
		if (elapsed >= SLOW_MS) {
			log.warn("Office revenue {} SLOW elapsedMs={} {}", op, elapsed, ctx);
		} else {
			log.debug("Office revenue {} elapsedMs={} {}", op, elapsed, ctx);
		}
		return result;
	}

	/** Log description. The search text itself is never logged (may contain taxpayer names / GSTINs). */
	private static String describe(RevenueDashboardFilter f) {
		if (f == null) {
			return "";
		}
		return "period=" + safe(f.retPeriod()) + " office=" + safe(f.office()) + " growth=" + safe(f.growthStatus())
				+ " search=" + (f.search() != null) + " page=" + f.page() + " size=" + f.size();
	}

	private static long ms(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}

	private static String blankToNull(String v) {
		return (v == null || v.isBlank()) ? null : v.trim();
	}

	/** Strips control characters (no forged log lines) and truncates long values. */
	private static String safe(String v) {
		if (v == null) {
			return null;
		}
		String c = v.replaceAll("\\p{Cntrl}", "_");
		return c.length() > MAX_LOG_VALUE ? c.substring(0, MAX_LOG_VALUE) + "..." : c;
	}
}



//package gov.com.ai.webapp.controller;
//
//import gov.com.ai.webapp.model.revenue.*;
//import gov.com.ai.webapp.service.revenue.OfficeRevenueDashboardService;
//import jakarta.validation.constraints.Max;
//import jakarta.validation.constraints.Min;
//import jakarta.validation.constraints.Pattern;
//import jakarta.validation.constraints.Size;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.http.CacheControl;
//import org.springframework.http.HttpHeaders;
//import org.springframework.http.MediaType;
//import org.springframework.http.ResponseEntity;
//import org.springframework.validation.annotation.Validated;
//import org.springframework.web.bind.annotation.*;
//import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
//import java.io.BufferedWriter;
//import java.io.IOException;
//import java.io.OutputStreamWriter;
//import java.nio.charset.StandardCharsets;
//import java.time.Duration;
//import java.util.List;
//import java.util.function.Supplier;
//
///**
// * GSTR-3B office revenue dashboard API.
// *
// * <p>Validation errors are mapped to HTTP 400 by {@link OfficeRevenueExceptionHandler}.
// */
//@Slf4j
//@Validated
//@RestController
//@RequiredArgsConstructor
//@RequestMapping("/gst/return-3b/office-revenue")
//public class OfficeRevenueController {
//
//	/** MMYYYY, month 01-12, year 2000-2099. */
//	private static final String PERIOD = "(0[1-9]|1[0-2])20\\d{2}";
//	private static final String PERIOD_MSG = "must be in MMYYYY format, e.g. 032025";	
//	private static final String GROWTH = "(GROWING|STABLE|DECLINING)?";
//	private static final String GROWTH_MSG = "must be GROWING, STABLE or DECLINING";
//
//	private static final int SUMMARY_PAGE = 0;
//	private static final int SUMMARY_SIZE = 25;
//	private static final int EXPORT_PAGE = 0;
//	private static final int EXPORT_SIZE = 100;
//	private static final long SLOW_MS = 2_000;
//	private static final byte[] UTF8_BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };
//
//	private static final CacheControl NO_STORE = CacheControl.noStore();
//	private static final CacheControl LOOKUP_CACHE = CacheControl.maxAge(Duration.ofMinutes(1)).cachePrivate();
//
//	private final OfficeRevenueDashboardService dashboard;
//	
////	private final OfficeRevenueBatchService batch;
//
//	/** Single-node guard. For several app instances use ShedLock or a DB lock row instead. */
//	
////	private final AtomicBoolean batchRunning = new AtomicBoolean(false);
//
//	// ------------------------------------------------------------------ reads
//
//	@GetMapping("/summary")
//	public ResponseEntity<OfficeRevenueDashboardResponse> summary(
//			@RequestParam @Pattern(regexp = PERIOD, message = PERIOD_MSG) String retPeriod,
//			@RequestParam(required = false) @Size(max = 100) String office,
//			@RequestParam(required = false) @Pattern(regexp = GROWTH, message = GROWTH_MSG) String growthStatus,
//			@RequestParam(required = false) @Size(max = 100) String search) {
//		RevenueDashboardFilter f = filter(retPeriod, office, growthStatus, search, SUMMARY_PAGE, SUMMARY_SIZE);
//		OfficeRevenueDashboardResponse body = timed("summary", f, () -> dashboard.summary(f));
//		return ResponseEntity.ok().cacheControl(NO_STORE).body(body);
//	}
//
//	@GetMapping("/list")
//	public ResponseEntity<PageResponse<OfficeRevenueRowResponse>> list(
//			@RequestParam @Pattern(regexp = PERIOD, message = PERIOD_MSG) String retPeriod,
//			@RequestParam(required = false) @Size(max = 100) String office,
//			@RequestParam(required = false) @Pattern(regexp = GROWTH, message = GROWTH_MSG) String growthStatus,
//			@RequestParam(required = false) @Size(max = 100) String search,
//			@RequestParam(defaultValue = "0") @Min(0) int page,
//			@RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
//		
//		RevenueDashboardFilter f = filter(retPeriod, office, growthStatus, search, page, size);
//		PageResponse<OfficeRevenueRowResponse> body = timed("list", f, () -> dashboard.page(f));
//		return ResponseEntity.ok().cacheControl(NO_STORE).body(body);
//	}
//
//	@GetMapping("/trend")
//	public ResponseEntity<List<OfficeRevenueTrendResponse>> trend(
//			@RequestParam @Pattern(regexp = PERIOD, message = PERIOD_MSG) String retPeriod,
//			@RequestParam(required = false) @Size(max = 100) String office,
//			@RequestParam(defaultValue = "12") @Min(1) @Max(36) int months) {
//		String o = blankToNull(office);
//		String ctx = "period=" + retPeriod + " office=" + safe(o) + " months=" + months;
//		List<OfficeRevenueTrendResponse> body = timed("trend", ctx, () -> dashboard.trend(retPeriod, o, months));
//		return ResponseEntity.ok().cacheControl(LOOKUP_CACHE).body(body);
//	}
//
//	@GetMapping("/periods")
//	public ResponseEntity<List<OptionDto>> periods() {
//		log.info("Enter into periods:--");
//		List<OptionDto> body = timed("periods", "", dashboard::periods);
//		return ResponseEntity.ok().cacheControl(LOOKUP_CACHE).body(body);
//	}
//
//	@GetMapping("/offices")
//	public ResponseEntity<List<OptionDto>> offices(
//			@RequestParam @Pattern(regexp = PERIOD, message = PERIOD_MSG) String retPeriod) {
//		List<OptionDto> body = timed("offices", "period=" + retPeriod, () -> dashboard.offices(retPeriod));
//		return ResponseEntity.ok().cacheControl(LOOKUP_CACHE).body(body);
//	}
//
//	// ------------------------------------------------------------------ batch
//
//	/**
//	 * Runs the batch synchronously. Only one run may be active at a time (409 otherwise).
//	 * Restrict to admins once method security is enabled, e.g. {@code @PreAuthorize("hasRole('ADMIN')")}.
//	 */
////	@PostMapping("/batch/run")
////	public ResponseEntity<RevenueBatchResponse> run(
////			@RequestParam @Pattern(regexp = PERIOD, message = PERIOD_MSG) String retPeriod) {
////		if (!batchRunning.compareAndSet(false, true)) {
////			log.warn("Office revenue batch rejected, already running requestedPeriod={}", retPeriod);
////			throw new ResponseStatusException(HttpStatus.CONFLICT, "An office revenue batch is already running");
////		}
////		long t0 = System.nanoTime();
////		try {
////			log.info("Office revenue batch started period={}", retPeriod);
////			RevenueBatchResponse result = batch.run(retPeriod);
////			log.info("Office revenue batch finished period={} elapsedMs={}", retPeriod, ms(t0));
////			return ResponseEntity.ok().cacheControl(NO_STORE).body(result);
////		} catch (RuntimeException e) {
////			log.error("Office revenue batch failed period={} elapsedMs={} cause={}", retPeriod, ms(t0), e.toString());
////			throw e; // stack trace is logged once by the exception handler
////		} finally {
////			batchRunning.set(false);
////		}
////	}
//
//	// ----------------------------------------------------------------- export
//
//	/** No {@code produces} restriction: clients sending another Accept header still get the file, not a 406. */
//	@GetMapping("/export")
//	public ResponseEntity<StreamingResponseBody> export(
//			@RequestParam @Pattern(regexp = PERIOD, message = PERIOD_MSG) String retPeriod,
//			@RequestParam(required = false) @Size(max = 100) String office,
//			@RequestParam(required = false) @Pattern(regexp = GROWTH, message = GROWTH_MSG) String growthStatus,
//			@RequestParam(required = false) @Size(max = 100) String search) {
//		RevenueDashboardFilter f = filter(retPeriod, office, growthStatus, search, EXPORT_PAGE, EXPORT_SIZE);
//		String ctx = describe(f);
//
//		StreamingResponseBody body = os -> {
//			long t0 = System.nanoTime();
//			log.info("Office revenue export started {}", ctx);
//			try {
//				os.write(UTF8_BOM);
//				BufferedWriter w = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), 65_536);
//				dashboard.export(f, w);
//				w.flush();
//				log.info("Office revenue export finished {} elapsedMs={}", ctx, ms(t0));
//			} catch (IOException e) {
//				// usually the client closed the connection
//				log.warn("Office revenue export interrupted {} elapsedMs={} cause={}", ctx, ms(t0), e.toString());
//				throw e;
//			} catch (RuntimeException e) {
//				// headers are already sent, so the client receives a truncated file
//				log.error("Office revenue export failed mid-stream {} elapsedMs={}", ctx, ms(t0), e);
//				throw e;
//			}
//		};
//
//		return ResponseEntity.ok().cacheControl(NO_STORE)
//				.header(HttpHeaders.CONTENT_DISPOSITION,
//						"attachment; filename=\"gst-3b-office-revenue-" + retPeriod + ".csv\"")
//				.contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(body);
//	}
//
//	// ---------------------------------------------------------------- helpers
//
//	private static RevenueDashboardFilter filter(String retPeriod, String office, String growthStatus, String search,
//			int page, int size) {
//		return new RevenueDashboardFilter(retPeriod, blankToNull(office), blankToNull(growthStatus),
//				blankToNull(search), page, size);
//	}
//
//	private <T> T timed(String op, RevenueDashboardFilter f, Supplier<T> call) {
//		return timed(op, describe(f), call);
//	}
//
//	private <T> T timed(String op, String ctx, Supplier<T> call) {
//		long t0 = System.nanoTime();
//		T result = call.get();
//		long elapsed = ms(t0);
//		if (elapsed >= SLOW_MS) {
//			log.warn("Office revenue {} SLOW elapsedMs={} {}", op, elapsed, ctx);
//		} else {
//			log.debug("Office revenue {} elapsedMs={} {}", op, elapsed, ctx);
//		}
//		return result;
//	}
//
//	private static String describe(RevenueDashboardFilter f) {
//		// Uses the values passed to the filter; avoids depending on accessor names of the model class.
//		return f == null ? "" : safe(f.toString());
//	}
//
//	private static long ms(long startNanos) {
//		return (System.nanoTime() - startNanos) / 1_000_000;
//	}
//
//	private static String blankToNull(String v) {
//		return (v == null || v.isBlank()) ? null : v.trim();
//	}
//
//	/** Strips control characters so user input cannot forge log lines. */
//	private static String safe(String v) {
//		return v == null ? null : v.replaceAll("[\\r\\n\\t]", "_");
//	}
//}