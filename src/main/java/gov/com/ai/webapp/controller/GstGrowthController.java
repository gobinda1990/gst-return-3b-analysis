package gov.com.ai.webapp.controller;

import gov.com.ai.webapp.model.ReturnPeriodOptionDto;
import gov.com.ai.webapp.model.dto.*;
import gov.com.ai.webapp.service.GstGrowthService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.springframework.validation.annotation.Validated;

import org.springframework.web.bind.annotation.*;

import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStreamWriter;

import java.nio.charset.StandardCharsets;

import java.util.List;

@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/gst/return-3b/growth")
/*
 * FIX: CORS is now configured globally in CorsConfig (see
 * gov.com.ai.webapp.config), not per-controller. A controller-level
 * @CrossOrigin only covers requests this controller actually handles - a
 * request that doesn't match any mapping here (e.g. a frontend/backend
 * base-path mismatch) would fall through to Spring's default error
 * handling with no CORS headers at all, which is what the browser reports
 * as a generic network error. The global config in CorsConfig covers every
 * path, matched or not, and reads the same app.cors.allowed-origins
 * property.
 */
public class GstGrowthController {

	private static final String PERIOD_PATTERN = "(0[1-9]|1[0-2])\\d{4}";

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

		log.debug("GET /summary period={} office={}", period, office);

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

		log.debug("GET /taxpayers period={} office={} search={} trend={} riskLevel={} page={} size={}", period,
				office, search, trend, riskLevel, page, size);

		return ResponseEntity.ok(service.getTaxpayers(period, office, search, trend, riskLevel, page, size));
	}

	/*
	 * FIX: previously ResponseEntity.ok() committed the 200 status
	 * immediately, and the actual validation + query only ran once the
	 * StreamingResponseBody lambda executed — by which point the status
	 * code can no longer be changed. An invalid period, or a DB failure
	 * mid-export, silently produced a truncated "successful" CSV with
	 * nothing in the logs.
	 *
	 * The @Pattern below now rejects a malformed period with a proper 400
	 * (via GstGrowthExceptionHandler) *before* this method body — and
	 * therefore before the streaming response — ever starts. That covers
	 * the most common failure. A genuine failure that only surfaces once
	 * streaming has begun (e.g. the DB going away mid-export) still can't
	 * change the HTTP status once bytes have been sent — no code can fix
	 * that after the fact — but it's now caught, logged with full context,
	 * and rethrown so the connection aborts cleanly instead of failing
	 * silently.
	 */
	@GetMapping(value = "/export", produces = "text/csv")
	public ResponseEntity<StreamingResponseBody> export(
			@RequestParam @Pattern(regexp = PERIOD_PATTERN, message = "period must be MMYYYY") String period,

			@RequestParam(required = false) String office,

			@RequestParam(required = false) String search,

			@RequestParam(required = false) String trend,

			@RequestParam(required = false) String riskLevel) {

		log.info("GET /export period={} office={} search={} trend={} riskLevel={}", period, office, search, trend,
				riskLevel);

		String filename = "gst-3b-growth-" + period + ".csv";

		StreamingResponseBody body = outputStream -> {

			/*
			 * Do not close outputStream directly. Spring owns it.
			 */
			OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);

			try {

				service.exportCsv(period, office, search, trend, riskLevel, writer);

				writer.flush();

			} catch (Exception ex) {

				log.error("GST growth CSV export failed mid-stream period={} office={} search={} trend={} riskLevel={}",
						period, office, search, trend, riskLevel, ex);

				throw ex;
			}
		};

		return ResponseEntity.ok()

				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")

				.header(HttpHeaders.CACHE_CONTROL, "no-store")

				.contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))

				.body(body);
	}
}