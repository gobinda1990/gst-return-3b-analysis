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
 *   <li>Summary, periods and offices are cached; paged lists and the CSV export are not.</li>
 *   <li>Summaries that use free-text {@code search} are never cached (unbounded key space).</li>
 *   <li>Cache keys use normalised values (trimmed, upper-cased enums), so equivalent requests share an entry.</li>
 *   <li>Cached methods never return null (the cache manager rejects null values) and return immutable lists.</li>
 *   <li>Method bodies, and their "loaded" log lines, only run on a cache miss.</li>
 *   <li>Cache names, TTLs and sizes live in {@link CacheConfig}. Call {@link #evictDefaulterCaches()} from
 *       another bean after the batch has refreshed the data.</li>
 * </ul>
 *
 * <p>All validation failures throw {@link DashboardRequestException} (mapped to HTTP 400).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaulterDashboardService {

	public static final String CACHE_SUMMARY = CacheConfig.CACHE_DEFAULTER_SUMMARY;
	public static final String CACHE_PERIODS = CacheConfig.CACHE_DEFAULTER_PERIODS;
	public static final String CACHE_OFFICES = CacheConfig.CACHE_DEFAULTER_OFFICES;

	private static final Set<String> FILING_STATUSES = Set.of("NOT_DUE", "FILED_ON_TIME", "FILED_LATE", "NOT_FILED");
	private static final Set<String> DEFAULT_LEVELS = Set.of("NORMAL", "WARNING", "HIGH", "CRITICAL");
	private static final Set<String> YES_NO = Set.of("Y", "N");

	private static final Pattern PERIOD = Pattern.compile("(0[1-9]|1[0-2])\\d{4}");

	private static final int MAX_PAGE_SIZE = 100;
	private static final int MAX_RISK_LENGTH = 30;
	private static final int MAX_OFFICE_LENGTH = 100;
	private static final int MAX_SEARCH_LENGTH = 100;
	private static final long SLOW_MS = 2_000;

	private final DefaulterDashboardRepository repository;

	// ---------------------------------------------------------------- queries

	@Cacheable(cacheNames = CACHE_SUMMARY, key = "#root.target.summaryKey(#p0)",
			condition = "#root.target.cacheable(#p0)", sync = true)
	public DefaulterDashboardSummary summary(DefaulterDashboardFilter filter) {

		long started = System.nanoTime();
		DefaulterDashboardFilter v = validate(filter, false);

		DefaulterDashboardSummary response = Objects.requireNonNull(repository.summary(v),
				"repository.summary returned null");

		logLoaded("summary", started, "period=" + v.retPeriod() + " office=" + safe(v.office()) + " filingStatus="
				+ v.filingStatus() + " defaultLevel=" + v.defaultLevel() + " search=" + (v.search() != null)
				+ " total=" + response.total());

		return response;
	}

	/** Paged and filtered, so intentionally not cached. */
	public PageResponse<DefaulterDashboardRow> page(DefaulterDashboardFilter filter) {

		long started = System.nanoTime();
		DefaulterDashboardFilter v = validate(filter, false);

		PageResponse<DefaulterDashboardRow> response = Objects.requireNonNull(repository.page(v),
				"repository.page returned null");

		int rows = response.content() == null ? 0 : response.content().size();

		logLoaded("page", started, "period=" + v.retPeriod() + " office=" + safe(v.office()) + " page=" + v.page()
				+ " size=" + v.size() + " search=" + (v.search() != null) + " rows=" + rows + " total="
				+ response.totalElements());

		return response;
	}

	@Cacheable(cacheNames = CACHE_PERIODS, key = "'ALL'", sync = true)
	public List<OptionDto> periods() {

		long started = System.nanoTime();
		List<OptionDto> r = repository.periods();
		logLoaded("periods", started, "");

		return r == null ? List.of() : List.copyOf(r);
	}

	@Cacheable(cacheNames = CACHE_OFFICES, key = "#root.target.periodKey(#p0)", sync = true)
	public List<OptionDto> offices(String retPeriod) {

		long started = System.nanoTime();
		String period = validatePeriod(retPeriod);

		List<OptionDto> r = repository.offices(period);
		logLoaded("offices", started, "period=" + period);

		return r == null ? List.of() : List.copyOf(r);
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
				keyPart(upper(f.riskLevel())), keyPart(upper(f.defaultLevel())), keyPart(upper(f.gstr3aEligible())),
				Integer.toString(Math.max(f.page(), 0)), Integer.toString(clamp(f.size(), 1, MAX_PAGE_SIZE)));
	}

	/** Only filters without free-text search are cached. */
	public boolean cacheable(DefaulterDashboardFilter f) {
		return f != null && clean(f.search()) == null;
	}

	public String periodKey(String retPeriod) {
		return keyPart(retPeriod);
	}

	// ------------------------------------------------------------- validation

	private DefaulterDashboardFilter validate(DefaulterDashboardFilter filter, boolean export) {

		if (filter == null) {
			throw bad("Dashboard filter is required");
		}

		String period = validatePeriod(filter.retPeriod());

		// export streams every row; the repository must not apply paging to it
		int page = export ? 0 : Math.max(filter.page(), 0);
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

	/** Returns the trimmed period (the old version validated the trimmed value but passed the raw one on). */
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

		String c = value.replaceAll("\\p{Cntrl}", "_");

		return c.length() > 120 ? c.substring(0, 120) + "..." : c;
	}

	private static long ms(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}

	private static void logLoaded(String op, long startNanos, String ctx) {

		long elapsed = ms(startNanos);

		if (elapsed >= SLOW_MS) {
			log.warn("Defaulter dashboard {} loaded SLOW elapsedMs={} {}", op, elapsed, ctx);
		} else {
			log.info("Defaulter dashboard {} loaded elapsedMs={} {}", op, elapsed, ctx);
		}
	}
}