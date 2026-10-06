package gov.com.ai.webapp.service.revenue;

import gov.com.ai.webapp.config.CacheConfig;
import gov.com.ai.webapp.exception.DashboardRequestException;
import gov.com.ai.webapp.model.revenue.DefaulterHistoryResponse;
import gov.com.ai.webapp.repository.revenue.GstDefaulterHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Per-GSTIN return history for the defaulter dashboard.
 *
 * <p>Results are cached (see {@link CacheConfig#CACHE_DEFAULTER_HISTORY}); the method body, and therefore the
 * "loaded" log line, only runs on a cache miss. The cache is evicted by
 * {@code DefaulterDashboardService#evictDefaulterCaches()} after the batch has refreshed the data.
 * Invalid input is reported with {@link DashboardRequestException} (mapped to HTTP 400).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GstDefaulterHistoryService {

	public static final String CACHE_HISTORY = CacheConfig.CACHE_DEFAULTER_HISTORY;

	private static final int DEFAULT_MONTHS = 12;
	private static final int MAX_MONTHS = 60;
	private static final long SLOW_MS = 2_000;

	private static final Pattern GSTIN_PATTERN = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");

	private final GstDefaulterHistoryRepository repository;

	@Cacheable(cacheNames = CACHE_HISTORY, key = "#root.target.historyKey(#p0, #p1)", sync = true)
	@Transactional(readOnly = true)
	public List<DefaulterHistoryResponse> getHistory(String gstin, Integer months) {

		long started = System.nanoTime();

		String normalizedGstin = normalizeGstin(gstin);
		int requestedMonths = validateMonths(months);

		List<DefaulterHistoryResponse> rows = repository.findHistory(normalizedGstin, requestedMonths);
		List<DefaulterHistoryResponse> result = rows == null ? List.of() : List.copyOf(rows);

		long elapsed = (System.nanoTime() - started) / 1_000_000;
		if (elapsed >= SLOW_MS) {
			log.warn("Defaulter history loaded SLOW gstin={} months={} rows={} elapsedMs={}", mask(normalizedGstin),
					requestedMonths, result.size(), elapsed);
		} else {
			log.info("Defaulter history loaded gstin={} months={} rows={} elapsedMs={}", mask(normalizedGstin),
					requestedMonths, result.size(), elapsed);
		}

		return result;
	}

	/** Trims, upper-cases and validates a GSTIN. Used by the controller before it logs or calls the service. */
	public String normalizeGstin(String gstin) {

		if (gstin == null || gstin.isBlank()) {
			throw new DashboardRequestException("GSTIN is required");
		}

		String normalized = gstin.trim().toUpperCase(Locale.ROOT);

		if (!GSTIN_PATTERN.matcher(normalized).matches()) {
			// the rejected value is deliberately not echoed back or logged
			log.warn("Rejected defaulter history request: invalid GSTIN format (length={})", normalized.length());
			throw new DashboardRequestException("Invalid GSTIN format");
		}

		return normalized;
	}

	/** Masked form for logs, e.g. 27*****1Z5. Safe for any input. */
	public static String mask(String gstin) {
		if (gstin == null || gstin.length() < 8) {
			return "***";
		}
		return gstin.substring(0, 2) + "*****" + gstin.substring(gstin.length() - 3);
	}

	/** Cache key helper (public because SpEL calls it). Never throws: invalid input just produces a key. */
	public String historyKey(String gstin, Integer months) {
		String g = gstin == null ? "-" : gstin.trim().toUpperCase(Locale.ROOT);
		return g + "|" + (months == null ? DEFAULT_MONTHS : months);
	}

	private int validateMonths(Integer months) {

		if (months == null) {
			return DEFAULT_MONTHS;
		}

		if (months < 1 || months > MAX_MONTHS) {
			throw new DashboardRequestException("months must be between 1 and " + MAX_MONTHS);
		}

		return months;
	}
}