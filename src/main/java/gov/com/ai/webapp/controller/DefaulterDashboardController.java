package gov.com.ai.webapp.controller;

import com.fasterxml.jackson.databind.JsonNode;
import gov.com.ai.webapp.exception.DashboardRequestException;
import gov.com.ai.webapp.model.revenue.DefaulterDashboardFilter;
import gov.com.ai.webapp.model.revenue.DefaulterDashboardRow;
import gov.com.ai.webapp.model.revenue.DefaulterDashboardSummary;
import gov.com.ai.webapp.model.revenue.DefaulterHistoryResponse;
import gov.com.ai.webapp.model.revenue.OptionDto;
import gov.com.ai.webapp.model.revenue.PageResponse;
import gov.com.ai.webapp.repository.CommonUserRepo;
import gov.com.ai.webapp.service.revenue.DefaulterDashboardService;
import gov.com.ai.webapp.service.revenue.GstDefaulterHistoryService;
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
import org.springframework.web.bind.annotation.PathVariable;
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
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Slf4j
@Validated
@RestController
@RequestMapping("/gst/return-3b/defaulters")
@RequiredArgsConstructor
public class DefaulterDashboardController {

	private static final String PERIOD = "(0[1-9]|1[0-2])\\d{4}";
	private static final String PERIOD_MSG = "must be in MMYYYY format, e.g. 032025";

	private static final java.util.regex.Pattern CONTROL_CHARS = java.util.regex.Pattern.compile("\\p{Cntrl}");

	private static final int DEFAULT_PAGE_SIZE = 25;
	private static final int MAX_PAGE_SIZE = 100; // same limit as the service
	private static final int MAX_PAGE = 100_000; // same limit as the service
	private static final int MAX_MONTHS = 60;
	private static final int MAX_LOG_VALUE = 200;

	/**
	 * Each export holds a DB connection and a request thread for its whole
	 * duration.
	 */
	private static final int MAX_CONCURRENT_EXPORTS = 4;

	/**
	 * Legacy rule: a token with NO roles is treated as Super Admin. This is
	 * fail-open; set to false as soon as every token is known to carry a role.
	 */
	private static final boolean LEGACY_NO_ROLE_IS_SUPER_ADMIN = true;

	private static final CacheControl NO_STORE = CacheControl.noStore();
	private static final CacheControl LOOKUP_CACHE = CacheControl.maxAge(Duration.ofMinutes(1)).cachePrivate();

	private final Semaphore exportPermits = new Semaphore(MAX_CONCURRENT_EXPORTS);

	private final DefaulterDashboardService service;
	private final GstDefaulterHistoryService historyService;
	private final CommonUserRepo commonUserRepo;
	private final JwtUtil jwtUtil;

	// ------------------------------------------------------------------ reads

	@GetMapping("/periods")
	public ResponseEntity<List<OptionDto>> periods() {
		return ResponseEntity.ok().cacheControl(LOOKUP_CACHE).body(service.periods());
	}

	/**
	 * Offices the caller may pick in the dropdown. Super Admin: every office for
	 * the period. Others: the offices under their assigned office ids (the period
	 * does not narrow those).
	 *
	 * <p>
	 * NOTE: this only filters the dropdown. /summary, /list, /export and /history
	 * do not enforce the same scope, see the review notes.
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
			return ok(service.offices(period));
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
				assigned = service.findChargeCdOffices(officeId);
			} catch (DashboardRequestException ex) {
				// one malformed id in the assignment data must not break the whole dropdown
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

	@GetMapping("/summary")
	public ResponseEntity<DefaulterDashboardSummary> summary(
			@RequestParam @Pattern(regexp = PERIOD, message = PERIOD_MSG) String retPeriod,
			@RequestParam(required = false) @Size(max = 100) String office,
			@RequestParam(required = false) @Size(max = 30) String filingStatus,
			@RequestParam(required = false) @Size(max = 30) String riskLevel,
			@RequestParam(required = false) @Size(max = 30) String defaultLevel,
			@RequestParam(required = false) @Size(max = 5) String gstr3aEligible,
			@RequestParam(required = false) @Size(max = 100) String search) {

		log.debug("GET defaulters/summary period={} office={} filingStatus={} defaultLevel={}", retPeriod, safe(office),
				safe(filingStatus), safe(defaultLevel));

		DefaulterDashboardSummary body = service.summary(filter(retPeriod, office, filingStatus, riskLevel,
				defaultLevel, gstr3aEligible, search, 0, DEFAULT_PAGE_SIZE));

		return ResponseEntity.ok().cacheControl(NO_STORE).body(body);
	}

	@GetMapping("/list")
	public ResponseEntity<PageResponse<DefaulterDashboardRow>> list(
			@RequestParam @Pattern(regexp = PERIOD, message = PERIOD_MSG) String retPeriod,
			@RequestParam(required = false) @Size(max = 100) String office,
			@RequestParam(required = false) @Size(max = 30) String filingStatus,
			@RequestParam(required = false) @Size(max = 30) String riskLevel,
			@RequestParam(required = false) @Size(max = 30) String defaultLevel,
			@RequestParam(required = false) @Size(max = 5) String gstr3aEligible,
			@RequestParam(required = false) @Size(max = 100) String search,
			@RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
			@RequestParam(defaultValue = "25") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

		// an unbounded size would let one request load the whole table; use /export for
		// everything
		log.debug("GET defaulters/list period={} office={} page={} size={}", retPeriod, safe(office), page, size);

		PageResponse<DefaulterDashboardRow> body = service.page(
				filter(retPeriod, office, filingStatus, riskLevel, defaultLevel, gstr3aEligible, search, page, size));

		return ResponseEntity.ok().cacheControl(NO_STORE).body(body);
	}

	@GetMapping("/{gstin}/history")
	public ResponseEntity<List<DefaulterHistoryResponse>> getHistory(@PathVariable @Size(max = 15) String gstin,
			@RequestParam(defaultValue = "12") @Min(1) @Max(MAX_MONTHS) int months) {

		// validate first: the masked value is only safe to build from a well-formed
		// GSTIN
		String id = historyService.normalizeGstin(gstin);

		log.debug("GET defaulters/history gstin={} months={}", GstDefaulterHistoryService.mask(id), months);

		return ResponseEntity.ok().cacheControl(LOOKUP_CACHE).body(historyService.getHistory(id, months));
	}

	// ----------------------------------------------------------------- export

	/**
	 * CSV export, streamed row by row.
	 *
	 * <p>
	 * The filter is validated BEFORE the stream starts: once streaming begins the
	 * 200 status is already sent, so a late failure could only produce a truncated
	 * file. Concurrent exports are capped (429 when full).
	 *
	 * <p>
	 * There is intentionally no {@code produces}: with
	 * {@code produces = "text/csv"} a request whose Accept header does not include
	 * text/csv (axios often sends application/json) is rejected with a 406 before
	 * the method runs.
	 *
	 * <p>
	 * Long exports also need {@code spring.mvc.async.request-timeout} set high
	 * enough (default is 30s on some containers), otherwise the stream is cut
	 * mid-file.
	 */
	@GetMapping("/export")
	public ResponseEntity<StreamingResponseBody> export(
			@RequestParam @Pattern(regexp = PERIOD, message = PERIOD_MSG) String retPeriod,
			@RequestParam(required = false) @Size(max = 100) String office,
			@RequestParam(required = false) @Size(max = 30) String filingStatus,
			@RequestParam(required = false) @Size(max = 30) String riskLevel,
			@RequestParam(required = false) @Size(max = 30) String defaultLevel,
			@RequestParam(required = false) @Size(max = 5) String gstr3aEligible,
			@RequestParam(required = false) @Size(max = 100) String search) {

		// full export: the service streams rows instead of paging them
		final DefaulterDashboardFilter filter = service.validateForExport(
				filter(retPeriod, office, filingStatus, riskLevel, defaultLevel, gstr3aEligible, search, 0, 1));

		final String period = filter.retPeriod();

		if (!exportPermits.tryAcquire()) {
			log.warn("Defaulter export rejected, {} exports already running period={}", MAX_CONCURRENT_EXPORTS, period);
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
					"Too many exports are running, please retry shortly");
		}

		try {
			log.info("GET defaulters/export period={} office={} filingStatus={} defaultLevel={} search={}", period,
					safe(filter.office()), safe(filter.filingStatus()), safe(filter.defaultLevel()),
					filter.search() != null);

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
					// the response is already committed here, so the client just sees a truncated
					// file
					log.error("Defaulter export FAILED period={} elapsedMs={}", period, elapsedMs(started), ex);
					throw ex;

				} finally {
					exportPermits.release();
				}
			};

			String filename = "gst-3b-defaulters-" + period + ".csv";

			return ResponseEntity.ok()
					.header(HttpHeaders.CONTENT_DISPOSITION,
							ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build()
									.toString())
					.cacheControl(NO_STORE).contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
					.body(body);

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

	/**
	 * Any role that normalises to SUPER ADMIN counts (ROLE_ prefix, case, '_' vs '
	 * ' are ignored).
	 */
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

	private static DefaulterDashboardFilter filter(String retPeriod, String office, String filingStatus,
			String riskLevel, String defaultLevel, String gstr3aEligible, String search, int page, int size) {

		return new DefaulterDashboardFilter(retPeriod, office, filingStatus, riskLevel, defaultLevel, gstr3aEligible,
				search, page, size);
	}

	/**
	 * Strips control characters (no forged log lines) and truncates long values.
	 */
	private static String safe(String v) {

		if (v == null) {
			return null;
		}

		String c = CONTROL_CHARS.matcher(v).replaceAll("_");

		return c.length() > MAX_LOG_VALUE ? c.substring(0, MAX_LOG_VALUE) + "..." : c;
	}

	private static long elapsedMs(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000L;
	}
}