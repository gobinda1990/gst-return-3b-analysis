package gov.com.ai.webapp.service.revenue;

import gov.com.ai.webapp.config.CacheConfig;
import gov.com.ai.webapp.exception.RevenueRequestException;
import gov.com.ai.webapp.model.revenue.OfficeRevenueDashboardResponse;
import gov.com.ai.webapp.model.revenue.OfficeRevenueRowResponse;
import gov.com.ai.webapp.model.revenue.OfficeRevenueTrendResponse;
import gov.com.ai.webapp.model.revenue.OptionDto;
import gov.com.ai.webapp.model.revenue.PageResponse;
import gov.com.ai.webapp.model.revenue.RevenueDashboardFilter;
import gov.com.ai.webapp.repository.revenue.OfficeRevenueDashboardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Office revenue dashboard queries.
 *
 * <p>Caching notes:
 * <ul>
 *   <li>Cache keys are built from <b>normalised</b> values, so {@code office=""}, {@code office=null} and
 *       {@code growthStatus=high} / {@code HIGH} share one entry.</li>
 *   <li>Summaries that use free-text {@code search} are never cached (unbounded key space).</li>
 *   <li>Cached methods never return null (the cache manager rejects null values) and return immutable lists.</li>
 *   <li>Cache names, TTLs and sizes are defined once in {@link CacheConfig}.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfficeRevenueDashboardService {

	public static final String CACHE_SUMMARY = CacheConfig.CACHE_OFFICE_REVENUE_SUMMARY;
	public static final String CACHE_TREND = CacheConfig.CACHE_OFFICE_REVENUE_TREND;
	public static final String CACHE_PERIODS = CacheConfig.CACHE_OFFICE_REVENUE_PERIODS;
	public static final String CACHE_OFFICES = CacheConfig.CACHE_OFFICE_REVENUE_OFFICES;

	/**
	 * Single source of truth for the growth-status filter. The controller regex must list the same values,
	 * and both must match the values stored in the DB.
	 */
	public static final Set<String> GROWTH_STATUSES = Set.of("HIGH", "MEDIUM", "LOW", "STABLE", "DECLINING",
			"NO_BASE");

	private static final Pattern PERIOD = Pattern.compile("(0[1-9]|1[0-2])20\\d{2}");
	private static final int MAX_SEARCH = 100;
	private static final int MAX_OFFICE = 100; // keep equal to @Size(max) in the controller
	private static final int MAX_PAGE_SIZE = 100;
	private static final int MAX_MONTHS = 36;
	private static final long SLOW_MS = 2_000;
	private static final String CRLF = "\r\n";
	private static final String CSV_HEADER = "Return Period,Office Code,Office Name,Filed GSTINs,Taxable Value,"
			+ "Output Tax,IGST,CGST,SGST,Cess,Eligible ITC,Utilized ITC,Cash Tax,MoM Output %,YoY Output %,"
			+ "MoM Cash %,YoY Cash %,Growth Trend,Growth Status";

	private final OfficeRevenueDashboardRepository repo;

	// ---------------------------------------------------------------- queries
	// Method bodies only run on a cache miss, so the "loaded" log lines double as miss counters.

	@Cacheable(cacheNames = CACHE_SUMMARY, key = "#root.target.summaryKey(#p0)",
			condition = "#root.target.cacheable(#p0)", sync = true)
	public OfficeRevenueDashboardResponse summary(RevenueDashboardFilter f) {
		long t0 = System.nanoTime();
		RevenueDashboardFilter v = valid(f);
		OfficeRevenueDashboardResponse r = Objects.requireNonNull(repo.summary(v), "repo.summary returned null");
		logLoaded("summary", t0, "period=" + v.retPeriod() + " office=" + v.office() + " growth="
				+ v.growthStatus() + " search=" + (v.search() != null));
		return r;
	}

	/** Paged and filtered, so intentionally not cached. */
	public PageResponse<OfficeRevenueRowResponse> page(RevenueDashboardFilter f) {
		long t0 = System.nanoTime();
		RevenueDashboardFilter v = valid(f);
		PageResponse<OfficeRevenueRowResponse> r = repo.page(v);
		logLoaded("page", t0, "period=" + v.retPeriod() + " office=" + v.office() + " growth=" + v.growthStatus()
				+ " search=" + (v.search() != null) + " page=" + v.page() + " size=" + v.size());
		return r;
	}

	@Cacheable(cacheNames = CACHE_TREND, key = "#root.target.trendKey(#p0, #p1, #p2)", sync = true)
	public List<OfficeRevenueTrendResponse> trend(String p, String o, int m) {
		long t0 = System.nanoTime();
		String period = period(p);
		String office = office(o);
		int months = clamp(m, 1, MAX_MONTHS);
		List<OfficeRevenueTrendResponse> r = repo.trend(period, office, months);
		logLoaded("trend", t0, "period=" + period + " office=" + office + " months=" + months);
		return r == null ? List.of() : List.copyOf(r);
	}

	@Cacheable(cacheNames = CACHE_PERIODS, key = "'ALL'", sync = true)
	public List<OptionDto> periods() {
		long t0 = System.nanoTime();
		List<OptionDto> r = repo.periods();
		logLoaded("periods", t0, "");
		return r == null ? List.of() : List.copyOf(r);
	}

	@Cacheable(cacheNames = CACHE_OFFICES, key = "#root.target.periodKey(#p0)", sync = true)
	public List<OptionDto> offices(String p) {
		long t0 = System.nanoTime();
		String period = period(p);
		List<OptionDto> r = repo.offices(period);
		logLoaded("offices", t0, "period=" + period);
		return r == null ? List.of() : List.copyOf(r);
	}

	// ----------------------------------------------------------------- export

	/**
	 * Validates and normalises a filter. The controller calls this BEFORE starting the streaming response,
	 * so a bad request gets a proper 400 instead of an empty 200 CSV.
	 */
	public RevenueDashboardFilter validate(RevenueDashboardFilter f) {
		return valid(f);
	}

	public void export(RevenueDashboardFilter f, BufferedWriter w) throws IOException {
		RevenueDashboardFilter v = valid(f);
		long t0 = System.nanoTime();
		AtomicLong rows = new AtomicLong();
		w.write(CSV_HEADER);
		w.write(CRLF);
		try {
			repo.streamExport(v, r -> {
				try {
					String[] a = { text(r.retPeriod()), text(r.stJuri()), text(r.officeName()),
							Long.toString(r.filedGstins()), num(r.taxableValue()), num(r.outputTax()), num(r.igst()),
							num(r.cgst()), num(r.sgst()), num(r.cess()), num(r.eligibleItc()), num(r.utilizedItc()),
							num(r.cashTaxPaid()), num(r.momOutputGrowth()), num(r.yoyOutputGrowth()),
							num(r.momCashGrowth()), num(r.yoyCashGrowth()), text(r.growthTrend()),
							text(r.growthStatus()) };
					w.write(String.join(",", a));
					w.write(CRLF);
					rows.incrementAndGet();
				} catch (IOException e) {
					throw new UncheckedIOException(e); // client went away; stops the DB cursor
				}
			});
		} catch (UncheckedIOException e) {
			log.warn("Export stopped after rows={} period={} cause={}", rows.get(), v.retPeriod(),
					e.getCause().toString());
			throw e.getCause();
		}
		w.flush();
		log.info("Export rows={} period={} office={} growth={} search={} elapsedMs={}", rows.get(), v.retPeriod(),
				v.office(), v.growthStatus(), v.search() != null, ms(t0));
	}

	// ------------------------------------------------------------------ cache

	/**
	 * Call after the monthly batch has refreshed data. Must be invoked from another bean
	 * (self-invocation bypasses the Spring proxy and would evict nothing).
	 */
	@CacheEvict(cacheNames = { CACHE_SUMMARY, CACHE_TREND, CACHE_PERIODS, CACHE_OFFICES }, allEntries = true)
	public void evictDashboardCaches() {
		log.info("Office revenue dashboard caches evicted");
	}

	// Key helpers are public because SpEL calls them. They never throw: invalid input simply produces
	// a key, and the method body then rejects it (exceptions are not cached).

	public String summaryKey(RevenueDashboardFilter f) {
		if (f == null) {
			return "NULL";
		}
		return String.join("|", keyPart(f.retPeriod()), keyPart(f.office()), keyPart(upper(f.growthStatus())),
				Integer.toString(Math.max(f.page(), 0)), Integer.toString(clamp(f.size(), 1, MAX_PAGE_SIZE)));
	}

	/** Only filters without free-text search are cached. */
	public boolean cacheable(RevenueDashboardFilter f) {
		return f != null && norm(f.search()) == null;
	}

	public String trendKey(String p, String o, int m) {
		return keyPart(p) + "|" + keyPart(o) + "|" + clamp(m, 1, MAX_MONTHS);
	}

	public String periodKey(String p) {
		return keyPart(p);
	}

	// ------------------------------------------------------------- validation

	private RevenueDashboardFilter valid(RevenueDashboardFilter f) {
		if (f == null) {
			throw bad("Filter required");
		}
		return new RevenueDashboardFilter(period(f.retPeriod()), office(f.office()), growth(f.growthStatus()),
				search(f.search()), Math.max(f.page(), 0), clamp(f.size(), 1, MAX_PAGE_SIZE));
	}

	private String period(String p) {
		String x = norm(p);
		if (x == null || !PERIOD.matcher(x).matches()) {
			throw bad("retPeriod must be MMYYYY");
		}
		return x;
	}

	private String office(String o) {
		String x = norm(o);
		if (x != null && x.length() > MAX_OFFICE) {
			throw bad("office max length is " + MAX_OFFICE);
		}
		return x;
	}

	private String growth(String g) {
		String x = upper(g);
		if (x != null && !GROWTH_STATUSES.contains(x)) {
			throw bad("Invalid growthStatus, allowed: " + GROWTH_STATUSES);
		}
		return x;
	}

	private String search(String q) {
		String x = norm(q);
		if (x != null && x.length() > MAX_SEARCH) {
			throw bad("search max length is " + MAX_SEARCH);
		}
		return x;
	}

	private RevenueRequestException bad(String message) {
		log.warn("Rejected office revenue request: {}", message);
		return new RevenueRequestException(message);
	}

	// ---------------------------------------------------------------- helpers

	private static String norm(String s) {
		if (s == null) {
			return null;
		}
		String x = s.trim();
		return x.isEmpty() ? null : x;
	}

	private static String upper(String s) {
		String x = norm(s);
		return x == null ? null : x.toUpperCase(Locale.ROOT);
	}

	private static String keyPart(String s) {
		String x = norm(s);
		return x == null ? "-" : x;
	}

	private static int clamp(int v, int min, int max) {
		return Math.min(Math.max(v, min), max);
	}

	private static long ms(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}

	private static void logLoaded(String op, long startNanos, String ctx) {
		long elapsed = ms(startNanos);
		if (elapsed >= SLOW_MS) {
			log.warn("Office revenue {} loaded SLOW elapsedMs={} {}", op, elapsed, ctx);
		} else {
			log.debug("Office revenue {} loaded elapsedMs={} {}", op, elapsed, ctx);
		}
	}

	/** Numbers are written as-is: a leading '-' on a negative growth figure must not be treated as a formula. */
	private static String num(BigDecimal b) {
		return b == null ? "" : b.toPlainString();
	}

	/** Text cells: single line, CSV-quoted, and neutralised against spreadsheet formula injection. */
	private static String text(String s) {
		if (s == null) {
			return "";
		}
		String x = s.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
		if (!x.isEmpty() && "=+-@".indexOf(x.charAt(0)) >= 0) {
			x = "'" + x;
		}
		x = x.replace("\"", "\"\"");
		return (x.indexOf(',') >= 0 || x.indexOf('"') >= 0) ? "\"" + x + "\"" : x;
	}
}