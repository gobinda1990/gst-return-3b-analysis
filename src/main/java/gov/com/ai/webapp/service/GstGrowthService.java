package gov.com.ai.webapp.service;

import gov.com.ai.webapp.config.CacheConfig;
import gov.com.ai.webapp.exception.InvalidGrowthRequestException;
import gov.com.ai.webapp.model.ReturnPeriodOptionDto;
import gov.com.ai.webapp.model.dto.*;
import gov.com.ai.webapp.repository.GstGrowthRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import java.io.Writer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class GstGrowthService {

	private static final int MAX_PAGE_SIZE = 100;
	private static final int MAX_SEARCH_LENGTH = 100;
	private static final int MAX_LOG_VALUE_LENGTH = 120;

	private static final Pattern PERIOD_PATTERN = Pattern.compile("(0[1-9]|1[0-2])\\d{4}");

	private static final Set<String> ALLOWED_TRENDS = Set.of("STRONG_GROWTH", "GROWTH", "STABLE", "DECLINE",
			"DECLINING", "STRONG_DECLINE", "SHARP_DECLINE");

	private static final Set<String> ALLOWED_RISK_LEVELS = Set.of("HIGH", "MEDIUM", "LOW");

	private final GstGrowthRepository repository;

	// ------------------------------------------------------------------
	// Cached reads. Method bodies (and their logs) run only on a cache MISS.
	// NOTE: caching works only when called through the Spring proxy, i.e. from
	// the controller or another bean, never via this.method() inside this class.
	// ------------------------------------------------------------------

	@Cacheable(cacheNames = CacheConfig.CACHE_GROWTH_PERIODS, key = "'all'", unless = "#result == null || #result.isEmpty()")
	public List<ReturnPeriodOptionDto> getPeriods() {

		log.info("Cache MISS: fetching GST growth return periods");

		List<ReturnPeriodOptionDto> result = repository.findReturnPeriods();
		List<ReturnPeriodOptionDto> safe = result == null ? List.of() : List.copyOf(result);

		log.info("Fetched GST growth return periods count={}", safe.size());

		return safe;
	}

	@Cacheable(cacheNames = CacheConfig.CACHE_GROWTH_OFFICES, key = "T(gov.com.ai.webapp.config.GrowthCacheKeys).scope(#period, null)", unless = "#result == null || #result.isEmpty()")
	public List<OfficeOptionResponse> getOffices(String period) {

		String validPeriod = validatePeriod(period);

		log.debug("Cache MISS: fetching GST growth offices period={}", validPeriod);

		List<OfficeOptionResponse> result = repository.findOffices(validPeriod);

		return result == null ? List.of() : List.copyOf(result);
	}
	
	@Cacheable(cacheNames = CacheConfig.CACHE_GROWTH_OFFICES, key = "T(gov.com.ai.webapp.config.GrowthCacheKeys).scope(#period, null)", unless = "#result == null || #result.isEmpty()")
	public List<OfficeOptionResponse> findChargeCdOffices(String officeId) {		

		log.debug("Cache MISS: fetching GST growth offices period={}", officeId);

		List<OfficeOptionResponse> result = repository.findChargeCdOffices(officeId);

		return result == null ? List.of() : List.copyOf(result);
	}

	@Cacheable(cacheNames = CacheConfig.CACHE_GROWTH_SUMMARY, key = "T(gov.com.ai.webapp.config.GrowthCacheKeys).scope(#period, #office)", unless = "#result == null")
	public GrowthSummaryResponse getSummary(String period, String office) {

		String validPeriod = validatePeriod(period);
		String normalizedOffice = normalize(office);

		log.debug("Cache MISS: fetching GST growth summary period={} office={}", validPeriod,
				safeLog(normalizedOffice));

		return repository.findSummary(validPeriod, normalizedOffice);
	}

	// sync=true: concurrent misses for the same key run the query once
	// (prevents a thundering herd on expensive trend queries).
	@Cacheable(cacheNames = CacheConfig.CACHE_GROWTH_TREND, key = "T(gov.com.ai.webapp.config.GrowthCacheKeys).scope(#period, #office)", sync = true)
	public List<GrowthTrendResponse> getTrend(String period, String office) {

		String validPeriod = validatePeriod(period);
		String normalizedOffice = normalize(office);

		log.debug("Cache MISS: fetching GST growth trend period={} office={}", validPeriod,
				safeLog(normalizedOffice));

		List<GrowthTrendResponse> result = repository.findTrend(validPeriod, normalizedOffice);

		return result == null ? List.of() : List.copyOf(result);
	}

	// Free-text searches are high-cardinality and rarely repeated, so only
	// search-less pages (the default grid view and filters) are cached.
	@Cacheable(cacheNames = CacheConfig.CACHE_GROWTH_TAXPAYERS, key = "T(gov.com.ai.webapp.config.GrowthCacheKeys).taxpayers(#period, #office, #trend, #riskLevel, #page, #size)", condition = "#search == null || #search.trim().isEmpty()", unless = "#result == null")
	public PageResponse<GrowthRowResponse> getTaxpayers(String period, String office, String search, String trend,
			String riskLevel, int page, int size) {

		validatePagination(page, size);

		String validPeriod = validatePeriod(period);
		String normalizedOffice = normalize(office);
		String normalizedSearch = normalizeSearch(search);
		String normalizedTrend = normalizeTrend(trend);
		String normalizedRisk = normalizeRisk(riskLevel);

		log.debug("Cache MISS/BYPASS: fetching GST growth taxpayers period={} office={} search={} trend={} risk={} page={} size={}",
				validPeriod, safeLog(normalizedOffice), safeLog(normalizedSearch), safeLog(normalizedTrend),
				safeLog(normalizedRisk), page, size);

		return repository.findGrowthRows(validPeriod, normalizedOffice, normalizedSearch, normalizedTrend,
				normalizedRisk, page, size);
	}

	/**
	 * Call this (from another bean, e.g. after the monthly batch finishes or
	 * from an admin endpoint) so users don't see stale data until the TTLs expire.
	 */
	@Caching(evict = {
			@CacheEvict(cacheNames = CacheConfig.CACHE_GROWTH_PERIODS, allEntries = true),
			@CacheEvict(cacheNames = CacheConfig.CACHE_GROWTH_OFFICES, allEntries = true),
			@CacheEvict(cacheNames = CacheConfig.CACHE_GROWTH_SUMMARY, allEntries = true),
			@CacheEvict(cacheNames = CacheConfig.CACHE_GROWTH_TREND, allEntries = true),
			@CacheEvict(cacheNames = CacheConfig.CACHE_GROWTH_TAXPAYERS, allEntries = true) })
	public void evictGrowthCaches() {
		log.info("GST growth caches evicted");
	}

	// ------------------------------------------------------------------
	// CSV export: never cached (streams to a Writer, large, filter-specific)
	// ------------------------------------------------------------------

	public void exportCsv(String period, String office, String search, String trend, String riskLevel, Writer writer) {

		if (writer == null) {
			log.warn("GST growth CSV export rejected: writer is required (period={})", safeLog(period));
			throw new InvalidGrowthRequestException("CSV writer is required");
		}

		String validPeriod = validatePeriod(period);
		String normalizedOffice = normalize(office);
		String normalizedSearch = normalizeSearch(search);
		String normalizedTrend = normalizeTrend(trend);
		String normalizedRisk = normalizeRisk(riskLevel);

		log.info("Starting GST growth CSV export period={} office={} search={} trend={} risk={}", validPeriod,
				safeLog(normalizedOffice), safeLog(normalizedSearch), safeLog(normalizedTrend),
				safeLog(normalizedRisk));

		repository.exportCsv(validPeriod, normalizedOffice, normalizedSearch, normalizedTrend, normalizedRisk, writer);
	}

	/**
	 * @deprecated use the 6-arg exportCsv with riskLevel = null.
	 */
	@Deprecated
	public void exportCsv(String period, String office, String search, String trend, Writer writer) {
		exportCsv(period, office, search, trend, null, writer);
	}

	// ------------------------------------------------------------------
	// Validation / normalization
	// ------------------------------------------------------------------

	private String validatePeriod(String period) {

		String value = normalize(period);

		if (value == null || !PERIOD_PATTERN.matcher(value).matches()) {
			log.warn("Invalid GST growth period requested: '{}'", safeLog(value));
			throw new InvalidGrowthRequestException("Return period must be MMYYYY");
		}

		return value;
	}

	private void validatePagination(int page, int size) {

		if (page < 0) {
			log.warn("Invalid GST growth page requested: page={}", page);
			throw new InvalidGrowthRequestException("Page cannot be negative");
		}

		if (size < 1 || size > MAX_PAGE_SIZE) {
			log.warn("Invalid GST growth page size requested: size={} max={}", size, MAX_PAGE_SIZE);
			throw new InvalidGrowthRequestException("Page size must be between 1 and " + MAX_PAGE_SIZE);
		}
	}

	private String normalizeSearch(String search) {

		String value = normalize(search);

		if (value == null) {
			return null;
		}

		if (value.length() > MAX_SEARCH_LENGTH) {
			log.warn("GST growth search term rejected: length={} max={}", value.length(), MAX_SEARCH_LENGTH);
			throw new InvalidGrowthRequestException("Search cannot exceed " + MAX_SEARCH_LENGTH + " characters");
		}

		return value;
	}

	private String normalizeTrend(String trend) {

		String value = normalizeUpper(trend);

		if (value == null) {
			return null;
		}

		if (!ALLOWED_TRENDS.contains(value)) {
			log.warn("Invalid GST growth trend requested: '{}'", safeLog(value));
			throw new InvalidGrowthRequestException("Invalid growth trend");
		}

		return value;
	}

	private String normalizeRisk(String riskLevel) {

		String value = normalizeUpper(riskLevel);

		if (value == null) {
			return null;
		}

		if (!ALLOWED_RISK_LEVELS.contains(value)) {
			log.warn("Invalid GST growth risk level requested: '{}'", safeLog(value));
			throw new InvalidGrowthRequestException("Invalid risk level");
		}

		return value;
	}

	private String normalizeUpper(String value) {
		String normalized = normalize(value);
		return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
	}

	private String normalize(String value) {

		if (value == null) {
			return null;
		}

		String normalized = value.trim();

		return normalized.isEmpty() ? null : normalized;
	}

	/**
	 * Strips control characters and truncates, so user-supplied values can't
	 * forge log lines (log injection) or flood the log.
	 */
	private String safeLog(String value) {

		if (value == null) {
			return "-";
		}

		String cleaned = value.replaceAll("\\p{Cntrl}", "_");

		return cleaned.length() > MAX_LOG_VALUE_LENGTH ? cleaned.substring(0, MAX_LOG_VALUE_LENGTH) + "..."
				: cleaned;
	}
}