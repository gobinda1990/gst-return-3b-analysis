package gov.com.ai.webapp.controller;

import com.fasterxml.jackson.databind.JsonNode;
import gov.com.ai.webapp.exception.RevenueRequestException;
import gov.com.ai.webapp.model.revenue.OfficeRevenueDashboardResponse;
import gov.com.ai.webapp.model.revenue.OfficeRevenueRowResponse;
import gov.com.ai.webapp.model.revenue.OfficeRevenueTrendResponse;
import gov.com.ai.webapp.model.revenue.OptionDto;
import gov.com.ai.webapp.model.revenue.PageResponse;
import gov.com.ai.webapp.model.revenue.RevenueDashboardFilter;
import gov.com.ai.webapp.repository.CommonUserRepo;
import gov.com.ai.webapp.service.revenue.OfficeRevenueDashboardService;
import gov.com.ai.webapp.util.JwtUtil;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * GSTR-3B office revenue dashboard API (read-only + CSV export).
 *
 * <p>Errors are mapped by {@code gov.com.ai.webapp.exception.GlobalExceptionHandler} (400 validation, 401/429
 * from {@link ResponseStatusException}, 503/500 for database failures). The monthly batch endpoint lives with the
 * batch code; after a batch run it must call {@code OfficeRevenueDashboardService#evictDashboardCaches()}.
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

	private static final java.util.regex.Pattern CONTROL_CHARS = java.util.regex.Pattern.compile("\\p{Cntrl}");

	private static final int SUMMARY_PAGE = 0;
	private static final int SUMMARY_SIZE = 25;
	private static final int EXPORT_PAGE = 0;
	private static final int EXPORT_SIZE = 100; // ignored by the export query, which streams every row
	private static final int MAX_PAGE = 100_000; // same limit as the service
	private static final int MAX_PAGE_SIZE = 100;
	private static final int MAX_MONTHS = 36;
	private static final long SLOW_MS = 2_000;
	private static final int MAX_LOG_VALUE = 300;
	private static final byte[] UTF8_BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

	/** Each export holds a DB connection and a request thread for its whole duration. */
	private static final int MAX_CONCURRENT_EXPORTS = 4;

	/**
	 * Legacy rule: a token with NO roles is treated as Super Admin. This is fail-open; set to false as soon as
	 * every token is known to carry a role.
	 */
	private static final boolean LEGACY_NO_ROLE_IS_SUPER_ADMIN = true;

	private static final CacheControl NO_STORE = CacheControl.noStore();
	private static final CacheControl LOOKUP_CACHE = CacheControl.maxAge(Duration.ofMinutes(1)).cachePrivate();

	private final Semaphore exportPermits = new Semaphore(MAX_CONCURRENT_EXPORTS);

	private final OfficeRevenueDashboardService dashboard;
	private final CommonUserRepo commonUserRepo;
	private final JwtUtil jwtUtil;

	// ------------------------------------------------------------------ reads

	@GetMapping("/summary")
	public ResponseEntity<OfficeRevenueDashboardResponse> summary(
			@RequestParam @Pattern(regexp = PERIOD, message = PERIOD_MSG) String retPeriod,
			@RequestParam(required = false) @Size(max = 100) String office,
			@RequestParam(required = false) @Pattern(regexp = GROWTH, message = GROWTH_MSG) String growthStatus,
			@RequestParam(required = false) @Size(max = 100) String search) {

		RevenueDashboardFilter f = filter(retPeriod, office, growthStatus, search, SUMMARY_PAGE, SUMMARY_SIZE);
		OfficeRevenueDashboardResponse body = timed("summary", describe(f), () -> dashboard.summary(f));
		return ResponseEntity.ok().cacheControl(NO_STORE).body(body);
	}

	@GetMapping("/list")
	public ResponseEntity<PageResponse<OfficeRevenueRowResponse>> list(
			@RequestParam @Pattern(regexp = PERIOD, message = PERIOD_MSG) String retPeriod,
			@RequestParam(required = false) @Size(max = 100) String office,
			@RequestParam(required = false) @Pattern(regexp = GROWTH, message = GROWTH_MSG) String growthStatus,
			@RequestParam(required = false) @Size(max = 100) String search,
			@RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
			@RequestParam(defaultValue = "25") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

		// an unbounded size would let one request load the whole table; use /export for everything
		RevenueDashboardFilter f = filter(retPeriod, office, growthStatus, search, page, size);
		PageResponse<OfficeRevenueRowResponse> body = timed("list", describe(f), () -> dashboard.page(f));
		return ResponseEntity.ok().cacheControl(NO_STORE).body(body);
	}

	@GetMapping("/trend")
	public ResponseEntity<List<OfficeRevenueTrendResponse>> trend(
			@RequestParam @Pattern(regexp = PERIOD, message = PERIOD_MSG) String retPeriod,
			@RequestParam(required = false) @Size(max = 100) String office,
			@RequestParam(defaultValue = "12") @Min(1) @Max(MAX_MONTHS) int months) {

		String o = blankToNull(office);
		String ctx = "period=" + safe(retPeriod) + " office=" + safe(o) + " months=" + months;
		List<OfficeRevenueTrendResponse> body = timed("trend", ctx, () -> dashboard.trend(retPeriod, o, months));
		return ResponseEntity.ok().cacheControl(LOOKUP_CACHE).body(body);
	}

	@GetMapping("/periods")
	public ResponseEntity<List<OptionDto>> periods() {
		List<OptionDto> body = timed("periods", "", dashboard::periods);
		return ResponseEntity.ok().cacheControl(LOOKUP_CACHE).body(body);
	}

	/**
	 * Offices the caller may pick in the dropdown. Super Admin: every office for the period. Others: the offices
	 * under their assigned office ids (the period does not narrow those).
	 *
	 * <p>NOTE: this only filters the dropdown. /summary, /list, /trend and /export do not enforce the same scope,
	 * see the review notes.
	 */
	@GetMapping("/offices")
	public ResponseEntity<List<OptionDto>> offices(@AuthenticationPrincipal Jwt jwt,
			@RequestParam @Pattern(regexp = PERIOD, message = PERIOD_MSG) String retPeriod) {

		if (jwt == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
		}

		String period = retPeriod.trim();
		List<String> roles = jwtUtil.extractRoles(jwt);

		if (isSuperAdmin(roles)) {
			log.debug("Offices for period={} (all offices)", period);
			return ok(dashboard.offices(period));
		}

		String hrmsCode = jwtUtil.getHrmsCode(jwt);

		if (hrmsCode == null || hrmsCode.isBlank()) {
			log.warn("Offices requested without an HRMS code in the token, period={}", period);
			return ok(Collections.emptyList());
		}

		List<JsonNode> assignments = commonUserRepo.fetchAssignedOffices(hrmsCode);

		if (assignments == null || assignments.isEmpty()) {
			return ok(Collections.emptyList());
		}

		List<String> officeIds = assignments.stream().filter(Objects::nonNull).flatMap(node -> {
			JsonNode offices = node.path("offices");
			return offices.isArray() ? StreamSupport.stream(offices.spliterator(), false) : Stream.<JsonNode>empty();
		}).map(office -> office.path("officeId").asText("").trim()).filter(id -> !id.isBlank()).distinct().toList();

		if (officeIds.isEmpty()) {
			return ok(Collections.emptyList());
		}

		// each lookup is cached per office id in the service
		Map<String, OptionDto> unique = new LinkedHashMap<>();

		for (String officeId : officeIds) {
			List<OptionDto> assigned;

			try {
				assigned = dashboard.findChargeCdOffices(officeId);
			} catch (RevenueRequestException ex) {				
				log.warn("Skipping invalid assigned officeId={}: {}", safe(officeId), ex.getMessage());
				continue;
			}
			for (OptionDto office : assigned) {
				if (office != null && office.value() != null) {
					unique.putIfAbsent(office.value(), office);
				}
			}
		}

		log.debug("Offices resolved period={} assignedIds={} offices={}", period, officeIds.size(), unique.size());

		return ok(List.copyOf(unique.values()));
	}

	// ----------------------------------------------------------------- export

	/**
	 * Streams the CSV. The filter is validated BEFORE the stream starts: once streaming begins the 200 status is
	 * already sent and an error could only produce a truncated file. Concurrent exports are capped (429 when
	 * full). No {@code produces} restriction, so clients sending another Accept header still get the file
	 * instead of a 406.
	 *
	 * <p>Long exports also need {@code spring.mvc.async.request-timeout} set high enough, otherwise the stream is
	 * cut mid-file.
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

		if (!exportPermits.tryAcquire()) {
			log.warn("Office revenue export rejected, {} exports already running {}", MAX_CONCURRENT_EXPORTS, ctx);
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
					"Too many exports are running, please retry shortly");
		}

		try {
			StreamingResponseBody body = os -> {
				long t0 = System.nanoTime();
				log.info("Office revenue export started {}", ctx);
				try {
					os.write(UTF8_BOM);
					BufferedWriter w = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8),
							65_536);
					dashboard.export(f, w);
					w.flush();
					log.info("Office revenue export finished {} elapsedMs={}", ctx, ms(t0));
				} catch (IOException e) {
					// usually the client closed the connection
					log.warn("Office revenue export interrupted {} elapsedMs={} cause={}", ctx, ms(t0),
							e.toString());
					throw e;
				} catch (RuntimeException e) {
					// headers are already sent, so the client receives a truncated file
					log.error("Office revenue export failed mid-stream {} elapsedMs={}", ctx, ms(t0), e);
					throw e;
				} finally {
					exportPermits.release();
				}
			};

			String filename = "gst-3b-office-revenue-" + f.retPeriod() + ".csv";

			return ResponseEntity.ok().cacheControl(NO_STORE)
					.header(HttpHeaders.CONTENT_DISPOSITION,
							ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build()
									.toString())
					.contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(body);

		} catch (RuntimeException ex) {
			// the stream never started, so its finally block will not release the permit
			exportPermits.release();
			throw ex;
		}
	}

	// ---------------------------------------------------------------- helpers

	private static ResponseEntity<List<OptionDto>> ok(List<OptionDto> body) {
		// per-user data: private cache only
		return ResponseEntity.ok().cacheControl(LOOKUP_CACHE).body(body);
	}

	/** Any role that normalises to SUPER ADMIN counts (ROLE_ prefix, case, '_' vs ' ' are ignored). */
	private static boolean isSuperAdmin(List<String> roles) {

		if (roles == null || roles.isEmpty()) {
			if (LEGACY_NO_ROLE_IS_SUPER_ADMIN) {
				log.warn("Token has no roles: applying legacy Super Admin rule");
			}
			return LEGACY_NO_ROLE_IS_SUPER_ADMIN;
		}

		return roles.stream().filter(Objects::nonNull)
				.map(r -> r.trim().toUpperCase(Locale.ROOT).replaceFirst("^ROLE_", "").replace('_', ' '))
				.anyMatch("SUPER ADMIN"::equals);
	}

	private static RevenueDashboardFilter filter(String retPeriod, String office, String growthStatus, String search,
			int page, int size) {
		return new RevenueDashboardFilter(retPeriod, blankToNull(office), blankToNull(growthStatus),
				blankToNull(search), page, size);
	}

	private <T> T timed(String op, String ctx, Supplier<T> call) {
		long t0 = System.nanoTime();
		T result = call.get();
		long elapsed = ms(t0);
		if (elapsed >= SLOW_MS) {
			log.warn("Office revenue {} SLOW elapsedMs={} {}", op, elapsed, ctx);
		} else if (log.isDebugEnabled()) {
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
		String c = CONTROL_CHARS.matcher(v).replaceAll("_");
		return c.length() > MAX_LOG_VALUE ? c.substring(0, MAX_LOG_VALUE) + "..." : c;
	}
}