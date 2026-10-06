package gov.com.ai.webapp.controller;

import gov.com.ai.webapp.model.ReturnPeriodOptionDto;
import gov.com.ai.webapp.model.dto.*;
import gov.com.ai.webapp.service.GstGrowthService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/gst/return-3b/growth")
public class GstGrowthController {

	private static final String PERIOD_PATTERN = "(0[1-9]|1[0-2])\\d{4}";

	private static final MediaType CSV_MEDIA_TYPE = new MediaType("text", "csv", StandardCharsets.UTF_8);

	private final GstGrowthService service;

	@GetMapping("/periods")
	public ResponseEntity<List<ReturnPeriodOptionDto>> periods() {

		log.info("GET /periods");

		return ResponseEntity.ok(service.getPeriods());
	}

	@GetMapping("/offices")
	public ResponseEntity<List<OfficeOptionResponse>> offices(
			@RequestParam @Pattern(regexp = PERIOD_PATTERN, message = "period must be MMYYYY") String period) {

		log.debug("GET /offices period={}", period);

		return ResponseEntity.ok(service.getOffices(period));
	}

	@GetMapping("/summary")
	public ResponseEntity<GrowthSummaryResponse> summary(
			@RequestParam @Pattern(regexp = PERIOD_PATTERN, message = "period must be MMYYYY") String period,
			@RequestParam(required = false) String office) {

		log.info("GET /summary period={} office={}", period, office);

		return ResponseEntity.ok(service.getSummary(period, office));
	}

	@GetMapping("/trend")
	public ResponseEntity<List<GrowthTrendResponse>> trend(
			@RequestParam @Pattern(regexp = PERIOD_PATTERN, message = "period must be MMYYYY") String period,

			@RequestParam(required = false) String office) {

		log.debug("GET /trend period={} office={}", period, office);

		return ResponseEntity.ok(service.getTrend(period, office));
	}

	@GetMapping("/taxpayers")
	public ResponseEntity<PageResponse<GrowthRowResponse>> taxpayers(
			@RequestParam @Pattern(regexp = PERIOD_PATTERN, message = "period must be MMYYYY") String period,

			@RequestParam(required = false) String office,

			@RequestParam(required = false) String search,

			@RequestParam(required = false) String trend,

			@RequestParam(required = false) String riskLevel,

			@RequestParam(defaultValue = "0") @Min(0) int page,

			@RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {

		log.debug("GET /taxpayers period={} office={} search={} trend={} riskLevel={} page={} size={}", period, office,
				search, trend, riskLevel, page, size);

		return ResponseEntity.ok(service.getTaxpayers(period, office, search, trend, riskLevel, page, size));
	}

	/*
	 * FIX: previously ResponseEntity.ok() committed the 200 status immediately, and
	 * the actual validation + query only ran once the StreamingResponseBody lambda
	 * executed — by which point the status code can no longer be changed. An
	 * invalid period, or a DB failure mid-export, silently produced a truncated
	 * "successful" CSV with nothing in the logs.
	 *
	 * The @Pattern below now rejects a malformed period with a proper 400 (via
	 * GstGrowthExceptionHandler) *before* this method body — and therefore before
	 * the streaming response — ever starts. That covers the most common failure. A
	 * genuine failure that only surfaces once streaming has begun (e.g. the DB
	 * going away mid-export) still can't change the HTTP status once bytes have
	 * been sent — no code can fix that after the fact — but it's now caught, logged
	 * with full context, and rethrown so the connection aborts cleanly instead of
	 * failing silently.
	 */
	/**
	 * Export GST 3B growth analytics as CSV.
	 *
	 * Example:
	 *
	 * GET /gst/return-3b/growth/export ?period=062026 &office=KOLKATA
	 * &trend=STRONG_DECLINE &riskLevel=HIGH
	 */
	@GetMapping(value = "/export", produces = "text/csv")
	public ResponseEntity<byte[]> export(
			@RequestParam @Pattern(regexp = PERIOD_PATTERN, message = "period must be MMYYYY") String period,

			@RequestParam(required = false) String office,

			@RequestParam(required = false) String search,

			@RequestParam(required = false) String trend,

			@RequestParam(required = false) String riskLevel) {

		/*
		 * Normalize optional filters before passing them to the service/repository
		 * layer.
		 */
		final String normalizedPeriod = period.trim();

		final String normalizedOffice = normalizeNullable(office);

		final String normalizedSearch = normalizeNullable(search);

		final String normalizedTrend = normalizeNullable(trend);

		final String normalizedRiskLevel = normalizeNullable(riskLevel);

		log.info("GET /export period={} office={} search={} trend={} riskLevel={}", normalizedPeriod, normalizedOffice,
				normalizedSearch, normalizedTrend, normalizedRiskLevel);

		byte[] csv = generateCsv(normalizedPeriod, normalizedOffice, normalizedSearch, normalizedTrend,
				normalizedRiskLevel);

		String filename = "gst-3b-growth-" + normalizedPeriod + ".csv";

		ContentDisposition disposition = ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8)
				.build();

		return ResponseEntity.ok()

				.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())

				.header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")

				.header(HttpHeaders.PRAGMA, "no-cache")

				.contentType(CSV_MEDIA_TYPE)

				.contentLength(csv.length)

				.body(csv);
	}

	/**
	 * Generate complete CSV before committing the HTTP response.
	 *
	 * This is intentional:
	 *
	 * If CSV generation fails, Spring can still invoke the normal exception handler
	 * and return a proper error response.
	 */
	private byte[] generateCsv(String period, String office, String search, String trend, String riskLevel) {

		try (ByteArrayOutputStream output = new ByteArrayOutputStream(64 * 1024);

				OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {

			/*
			 * UTF-8 BOM.
			 *
			 * This improves compatibility with Microsoft Excel when taxpayer/trade/office
			 * names contain Unicode.
			 */
			writer.write('\uFEFF');

			service.exportCsv(period, office, search, trend, riskLevel, writer);

			writer.flush();

			return output.toByteArray();

		} catch (IOException ex) {

			log.error("Unable to generate GST growth CSV " + "period={} office={} search={} trend={} riskLevel={}",
					period, office, search, trend, riskLevel, ex);

			throw new IllegalStateException("Unable to generate GST growth CSV.", ex);

		} catch (RuntimeException ex) {

			log.error("GST growth CSV generation failed " + "period={} office={} search={} trend={} riskLevel={}",
					period, office, search, trend, riskLevel, ex);

			throw ex;
		}
	}

	/**
	 * Convert blank optional parameters to null.
	 */
	private String normalizeNullable(String value) {

		if (value == null) {
			return null;
		}

		String normalized = value.trim();

		return normalized.isEmpty() ? null : normalized;
	}
}