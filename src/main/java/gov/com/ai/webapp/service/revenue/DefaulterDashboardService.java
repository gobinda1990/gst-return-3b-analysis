package gov.com.ai.webapp.service.revenue;

import gov.com.ai.webapp.config.CacheConfig;
import gov.com.ai.webapp.exception.DashboardRequestException;
import gov.com.ai.webapp.model.revenue.DefaulterDashboardFilter;
import gov.com.ai.webapp.model.revenue.DefaulterDashboardRow;
import gov.com.ai.webapp.model.revenue.DefaulterDashboardSummary;
import gov.com.ai.webapp.model.revenue.OptionDto;
import gov.com.ai.webapp.model.revenue.PageResponse;
import gov.com.ai.webapp.repository.revenue.DefaulterDashboardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Defaulter dashboard queries.
 *
 * <p>Caching notes:
 * <ul>
 *   <li>Summary, periods, offices and charge-code offices are cached; paged lists and the CSV export are not.</li>
 *   <li>Summaries that use free-text {@code search} are never cached (unbounded key space).</li>
 *   <li>Cache keys use normalised values (trimmed, upper-cased enums), so equivalent requests share an entry.</li>
 *   <li>The summary key does NOT contain page/size: the summary does not depend on them.</li>
 *   <li>Cached methods never return null and return immutable, null-free lists.</li>
 *   <li>Method bodies, and their "loaded" log lines, only run on a cache miss.</li>
 *   <li>Cache names, TTLs and sizes live in {@link CacheConfig}. Call {@link #evictDefaulterCaches()} from
 *       another bean after the batch has refreshed the data.</li>
 * </ul>
 *
 * <p>All validation failures throw {@link DashboardRequestException} (mapped to HTTP 400). Infrastructure failures
 * ({@code DataAccessException}) are left to propagate to the controller advice (mapped to 503/500).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaulterDashboardService {

	public static final String CACHE_SUMMARY = CacheConfig.CACHE_DEFAULTER_SUMMARY;
	public static final String CACHE_PERIODS = CacheConfig.CACHE_DEFAULTER_PERIODS;
	public static final String CACHE_OFFICES = CacheConfig.CACHE_DEFAULTER_OFFICES;	
	public static final String CACHE_CHARGE_OFFICES = CacheConfig.CACHE_DEFAULTER_CHARGE_OFFICES;

	private static final Set<String> FILING_STATUSES = Set.of("NOT_DUE", "FILED_ON_TIME", "FILED_LATE", "NOT_FILED");
	private static final Set<String> DEFAULT_LEVELS = Set.of("NORMAL", "WARNING", "HIGH", "CRITICAL");
	private static final Set<String> YES_NO = Set.of("Y", "N");

	private static final Pattern PERIOD = Pattern.compile("(0[1-9]|1[0-2])\\d{4}");
	private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

	private static final int MAX_PAGE = 100_000; // keeps page * size far away from int overflow / absurd OFFSETs
	private static final int MAX_PAGE_SIZE = 100;
	private static final int MAX_RISK_LENGTH = 30;
	private static final int MAX_OFFICE_LENGTH = 100;
	private static final int MAX_SEARCH_LENGTH = 100;
	private static final int MAX_LOG_VALUE = 120;
	private static final long SLOW_MS = 2_000;

	private final DefaulterDashboardRepository repository;

	// ---------------------------------------------------------------- queries

	@Cacheable(cacheNames = CACHE_SUMMARY, key = "#root.target.summaryKey(#p0)",
			condition = "#root.target.cacheable(#p0)", sync = true)
	public DefaulterDashboardSummary summary(DefaulterDashboardFilter filter) {

		long started = System.nanoTime();
		DefaulterDashboardFilter v = validate(filter, false);

		DefaulterDashboardSummary response = requireResult(repository.summary(v), "summary");

		logLoaded("summary", started, "period=" + v.retPeriod() + " office=" + safe(v.office()) + " filingStatus="
				+ v.filingStatus() + " defaultLevel=" + v.defaultLevel() + " search=" + (v.search() != null)
				+ " total=" + response.total());

		return response;
	}

	/** Paged and filtered, so intentionally not cached. */
	public PageResponse<DefaulterDashboardRow> page(DefaulterDashboardFilter filter) {

		long started = System.nanoTime();
		DefaulterDashboardFilter v = validate(filter, false);

		PageResponse<DefaulterDashboardRow> response = requireResult(repository.page(v), "page");

		int rows = response.content() == null ? 0 : response.content().size();

		logLoaded("page", started, "period=" + v.retPeriod() + " office=" + safe(v.office()) + " page=" + v.page()
				+ " size=" + v.size() + " search=" + (v.search() != null) + " rows=" + rows + " total="
				+ response.totalElements());

		return response;
	}

	@Cacheable(cacheNames = CACHE_PERIODS, key = "'ALL'", sync = true)
	public List<OptionDto> periods() {

		long started = System.nanoTime();
		List<OptionDto> r = immutable(repository.periods());
		logLoaded("periods", started, "count=" + r.size());

		return r;
	}

	@Cacheable(cacheNames = CACHE_OFFICES, key = "#root.target.periodKey(#p0)", sync = true)
	public List<OptionDto> offices(String retPeriod) {

		long started = System.nanoTime();
		String period = validatePeriod(retPeriod);

		List<OptionDto> r = immutable(repository.offices(period));
		logLoaded("offices", started, "period=" + period + " count=" + r.size());

		return r;
	}

	/**
	 * Offices under one assigned office id (charge code). Cached per office id, so a user with many assigned
	 * offices hits the database only for ids nobody has asked for recently. Master data: not evicted by the batch.
	 */
	@Cacheable(cacheNames = CACHE_CHARGE_OFFICES, key = "#root.target.officeKey(#p0)", sync = true)
	public List<OptionDto> findChargeCdOffices(String officeId) {

		long started = System.nanoTime();
		String id = clean(officeId);

		if (id == null) {
			throw bad("officeId is required");
		}
		if (id.length() > MAX_OFFICE_LENGTH) {
			throw bad("officeId exceeds " + MAX_OFFICE_LENGTH + " characters");
		}

		List<OptionDto> r = immutable(repository.findChargeCdOffices(id));
		logLoaded("chargeOffices", started, "officeId=" + safe(id) + " count=" + r.size());

		return r;
	}

	// ----------------------------------------------------------------- export

	/**
	 * Validates and normalises an export filter. The controller calls this BEFORE starting the streaming
	 * response, so a bad request gets a proper 400 instead of an empty 200 CSV.
	 */
	public DefaulterDashboardFilter validateForExport(DefaulterDashboardFilter filter) {
		return validate(filter, true);
	}

	public void export(DefaulterDashboardFilter filter, Writer writer) throws IOException {

		if (writer == null) {
			throw new DashboardRequestException("CSV writer is required");
		}

		// idempotent: already-validated filters pass through unchanged
		DefaulterDashboardFilter v = validate(filter, true);
		long started = System.nanoTime();

		log.info("Defaulter dashboard CSV START period={} office={} filingStatus={} search={}", v.retPeriod(),
				safe(v.office()), v.filingStatus(), v.search() != null);

		repository.streamCsv(v, writer);

		log.info("Defaulter dashboard CSV COMPLETE period={} office={} elapsedMs={}", v.retPeriod(), safe(v.office()),
				ms(started));
	}

	// ------------------------------------------------------------------ cache

	/**
	 * Call after the batch has refreshed defaulter data. Must be invoked from another bean (self-invocation
	 * bypasses the Spring proxy and would evict nothing). Also clears the per-GSTIN history cache.
	 */
	@CacheEvict(cacheNames = { CACHE_SUMMARY, CACHE_PERIODS, CACHE_OFFICES,
			GstDefaulterHistoryService.CACHE_HISTORY }, allEntries = true)
	public void evictDefaulterCaches() {
		log.info("Defaulter dashboard caches evicted");
	}

	// Key helpers are public because SpEL calls them. They never throw: invalid input simply produces
	// a key, and the method body then rejects it (exceptions are not cached).

	public String summaryKey(DefaulterDashboardFilter f) {
		if (f == null) {
			return "NULL";
		}
		return String.join("|", keyPart(f.retPeriod()), keyPart(f.office()), keyPart(upper(f.filingStatus())),
				keyPart(upper(f.riskLevel())), keyPart(upper(f.defaultLevel())), keyPart(upper(f.gstr3aEligible())));
	}

	/** Only filters without free-text search are cached. */
	public boolean cacheable(DefaulterDashboardFilter f) {
		return f != null && clean(f.search()) == null;
	}

	public String periodKey(String retPeriod) {
		return keyPart(retPeriod);
	}

	public String officeKey(String officeId) {
		return keyPart(officeId);
	}

	// ------------------------------------------------------------- validation

	private DefaulterDashboardFilter validate(DefaulterDashboardFilter filter, boolean export) {

		if (filter == null) {
			throw bad("Dashboard filter is required");
		}

		String period = validatePeriod(filter.retPeriod());

		// export streams every row; the repository must not apply paging to it
		int page = export ? 0 : clamp(filter.page(), 0, MAX_PAGE);
		int size = export ? Integer.MAX_VALUE : clamp(filter.size(), 1, MAX_PAGE_SIZE);

		String office = clean(filter.office());
		if (office != null && office.length() > MAX_OFFICE_LENGTH) {
			throw bad("office exceeds " + MAX_OFFICE_LENGTH + " characters");
		}

		String filingStatus = upper(filter.filingStatus());
		if (filingStatus != null && !FILING_STATUSES.contains(filingStatus)) {
			throw bad("Invalid filingStatus, allowed: " + FILING_STATUSES);
		}

		String defaultLevel = upper(filter.defaultLevel());
		if (defaultLevel != null && !DEFAULT_LEVELS.contains(defaultLevel)) {
			throw bad("Invalid defaultLevel, allowed: " + DEFAULT_LEVELS);
		}

		String gstr3aEligible = upper(filter.gstr3aEligible());
		if (gstr3aEligible != null && !YES_NO.contains(gstr3aEligible)) {
			throw bad("gstr3aEligible must be Y or N");
		}

		String riskLevel = upper(filter.riskLevel());
		if (riskLevel != null && riskLevel.length() > MAX_RISK_LENGTH) {
			throw bad("riskLevel exceeds " + MAX_RISK_LENGTH + " characters");
		}

		String search = clean(filter.search());
		if (search != null && search.length() > MAX_SEARCH_LENGTH) {
			throw bad("search exceeds " + MAX_SEARCH_LENGTH + " characters");
		}

		return new DefaulterDashboardFilter(period, office, filingStatus, riskLevel, defaultLevel, gstr3aEligible,
				search, page, size);
	}

	/** Returns the trimmed period. */
	private String validatePeriod(String retPeriod) {

		String p = clean(retPeriod);

		if (p == null || !PERIOD.matcher(p).matches()) {
			throw bad("retPeriod must be MMYYYY");
		}

		return p;
	}

	private DashboardRequestException bad(String message) {
		log.warn("Rejected defaulter dashboard request: {}", message);
		return new DashboardRequestException(message);
	}

	// ---------------------------------------------------------------- helpers

	/** A null from the repository is a programming/data error (500), not a client error. */
	private static <T> T requireResult(T value, String op) {
		if (value == null) {
			throw new IllegalStateException("Defaulter dashboard repository returned null for " + op);
		}
		return value;
	}

	/** Immutable copy that tolerates null lists AND null elements (List.copyOf rejects both). */
	private static List<OptionDto> immutable(List<OptionDto> source) {
		return source == null ? List.of() : source.stream().filter(Objects::nonNull).toList();
	}

	private static String clean(String value) {

		if (value == null) {
			return null;
		}

		String v = value.trim();

		return v.isEmpty() ? null : v;
	}

	private static String upper(String value) {

		String v = clean(value);

		return v == null ? null : v.toUpperCase(Locale.ROOT);
	}

	private static String keyPart(String value) {

		String v = clean(value);

		return v == null ? "-" : v;
	}

	private static int clamp(int v, int min, int max) {
		return Math.min(Math.max(v, min), max);
	}

	/** Strips control characters (no forged log lines) and truncates long values. */
	private static String safe(String value) {

		if (value == null) {
			return null;
		}

		String c = CONTROL_CHARS.matcher(value).replaceAll("_");

		return c.length() > MAX_LOG_VALUE ? c.substring(0, MAX_LOG_VALUE) + "..." : c;
	}

	private static long ms(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}

	/** INFO on every cache miss is noisy at scale: DEBUG normally, WARN when slow. */
	private static void logLoaded(String op, long startNanos, String ctx) {

		long elapsed = ms(startNanos);

		if (elapsed >= SLOW_MS) {
			log.warn("Defaulter dashboard {} loaded SLOW elapsedMs={} {}", op, elapsed, ctx);
		} else if (log.isDebugEnabled()) {
			log.debug("Defaulter dashboard {} loaded elapsedMs={} {}", op, elapsed, ctx);
		}
	}
}