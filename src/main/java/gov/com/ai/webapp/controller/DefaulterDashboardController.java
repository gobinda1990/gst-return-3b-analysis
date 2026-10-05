package gov.com.ai.webapp.controller;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import gov.com.ai.webapp.exception.RevenueRequestException;
import gov.com.ai.webapp.model.revenue.DefaulterDashboardFilter;
import gov.com.ai.webapp.model.revenue.DefaulterDashboardRow;
import gov.com.ai.webapp.model.revenue.DefaulterDashboardSummary;
import gov.com.ai.webapp.model.revenue.DefaulterHistoryResponse;
import gov.com.ai.webapp.model.revenue.OptionDto;
import gov.com.ai.webapp.model.revenue.PageResponse;
import gov.com.ai.webapp.service.revenue.DefaulterDashboardService;
import gov.com.ai.webapp.service.revenue.GstDefaulterHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/gst/return-3b/defaulters")
@RequiredArgsConstructor
public class DefaulterDashboardController {

	private static final Pattern PERIOD = Pattern.compile("(0[1-9]|1[0-2])\\d{4}");
	private static final Pattern GSTIN = Pattern.compile("[0-9A-Z]{15}");

	private static final int MAX_PAGE_SIZE = 200;
	private static final int DEFAULT_PAGE_SIZE = 25;

	private final DefaulterDashboardService service;
	private final GstDefaulterHistoryService historyService;

	@GetMapping("/summary")
	public DefaulterDashboardSummary summary(@RequestParam String retPeriod,
			@RequestParam(required = false) String office, @RequestParam(required = false) String filingStatus,
			@RequestParam(required = false) String riskLevel, @RequestParam(required = false) String defaultLevel,
			@RequestParam(required = false) String gstr3aEligible, @RequestParam(required = false) String search) {

		String period = validPeriod(retPeriod);

		log.info("GET defaulters/summary period={} office={} filingStatus={} riskLevel={}", period, office,
				filingStatus, riskLevel);

		return service.summary(filter(period, office, filingStatus, riskLevel, defaultLevel, gstr3aEligible, search, 0,
				DEFAULT_PAGE_SIZE));
	}

	@GetMapping("/list")
	public PageResponse<DefaulterDashboardRow> list(@RequestParam String retPeriod,
			@RequestParam(required = false) String office, @RequestParam(required = false) String filingStatus,
			@RequestParam(required = false) String riskLevel, @RequestParam(required = false) String defaultLevel,
			@RequestParam(required = false) String gstr3aEligible, @RequestParam(required = false) String search,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {

		String period = validPeriod(retPeriod);

		// an unbounded size would let one request load the whole table; use /export for everything
		int safePage = Math.max(0, page);
		int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

		return service.page(filter(period, office, filingStatus, riskLevel, defaultLevel, gstr3aEligible, search,
				safePage, safeSize));
	}

	@GetMapping("/periods")
	public List<OptionDto> periods() {
		return service.periods();
	}

	@GetMapping("/offices")
	public List<OptionDto> offices(@RequestParam String retPeriod) {
		return service.offices(validPeriod(retPeriod));
	}

	@GetMapping("/{gstin}/history")
	public ResponseEntity<List<DefaulterHistoryResponse>> getHistory(@PathVariable String gstin,
			@RequestParam(defaultValue = "12") int months) {

		String id = validGstin(gstin);
		int safeMonths = Math.min(Math.max(1, months), 60);

		log.info("GET defaulters/history gstin={} months={}", mask(id), safeMonths);

		return ResponseEntity.ok(historyService.getHistory(id, safeMonths));
	}

	/**
	 * CSV export, streamed row by row.
	 *
	 * NOTE: there is intentionally NO "produces" here. With produces = "text/csv", any request whose Accept header
	 * does not include text/csv (axios instances often send "application/json") is rejected with
	 * HttpMediaTypeNotAcceptableException ("No acceptable representation") before the method even runs.
	 * A StreamingResponseBody writes directly, so the Content-Type is set explicitly below instead.
	 */
	@GetMapping("/export")
	public ResponseEntity<StreamingResponseBody> export(@RequestParam String retPeriod,
			@RequestParam(required = false) String office, @RequestParam(required = false) String filingStatus,
			@RequestParam(required = false) String riskLevel, @RequestParam(required = false) String defaultLevel,
			@RequestParam(required = false) String gstr3aEligible, @RequestParam(required = false) String search) {

		final String period = validPeriod(retPeriod);

		// full export: the service must stream rows, not page them
		final DefaulterDashboardFilter filter = filter(period, office, filingStatus, riskLevel, defaultLevel,
				gstr3aEligible, search, 0, Integer.MAX_VALUE);

		log.info("GET defaulters/export period={} office={} filingStatus={} riskLevel={}", period, office,
				filingStatus, riskLevel);

		StreamingResponseBody body = outputStream -> {

			long started = System.nanoTime();

			try (BufferedWriter writer = new BufferedWriter(
					new OutputStreamWriter(outputStream, StandardCharsets.UTF_8), 64 * 1024)) {

				// UTF-8 BOM so Microsoft Excel opens the file correctly
				writer.write('\uFEFF');

				service.export(filter, writer);
				writer.flush();

				log.info("Defaulter export finished period={} elapsedMs={}", period, elapsedMs(started));

			} catch (IOException ex) {
				// normally the browser closed the connection / user cancelled the download
				log.warn("Defaulter export aborted period={} elapsedMs={} cause={}", period, elapsedMs(started),
						ex.toString());
				throw ex;

			} catch (RuntimeException ex) {
				// the response is already committed here, so the client just sees a truncated file - log it loudly
				log.error("Defaulter export FAILED period={} elapsedMs={}", period, elapsedMs(started), ex);
				throw ex;
			}
		};

		String filename = "gst-3b-defaulters-" + period + ".csv";

		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename(filename).build().toString())
				.cacheControl(CacheControl.noStore())
				.contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).body(body);
	}

	// ------------------------------------------------------------------

	private DefaulterDashboardFilter filter(String retPeriod, String office, String filingStatus, String riskLevel,
			String defaultLevel, String gstr3aEligible, String search, int page, int size) {

		return new DefaulterDashboardFilter(retPeriod, office, filingStatus, riskLevel, defaultLevel, gstr3aEligible,
				search, page, size);
	}

	/** Also stops header injection through the export file name. */
	private String validPeriod(String retPeriod) {

		String p = retPeriod == null ? null : retPeriod.trim();

		if (p == null || !PERIOD.matcher(p).matches()) {
			throw new RevenueRequestException("retPeriod must be MMYYYY");
		}

		return p;
	}

	private String validGstin(String gstin) {

		String g = gstin == null ? null : gstin.trim().toUpperCase();

		if (g == null || !GSTIN.matcher(g).matches()) {
			throw new RevenueRequestException("gstin must be 15 letters/digits");
		}

		return g;
	}

	private String mask(String gstin) {
		return gstin.substring(0, 2) + "*****" + gstin.substring(gstin.length() - 4);
	}

	private long elapsedMs(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000L;
	}
}