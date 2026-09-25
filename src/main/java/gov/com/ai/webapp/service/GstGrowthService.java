package gov.com.ai.webapp.service;

import gov.com.ai.webapp.exception.InvalidGrowthRequestException;
import gov.com.ai.webapp.model.ReturnPeriodOptionDto;
import gov.com.ai.webapp.model.dto.*;
import gov.com.ai.webapp.repository.GstGrowthRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.Writer;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class GstGrowthService {

	private static final int MAX_PAGE_SIZE = 100;

	private static final int MAX_SEARCH_LENGTH = 100;

	private static final Set<String> ALLOWED_TRENDS = Set.of("STRONG_GROWTH", "GROWTH", "STABLE", "DECLINE",
			"DECLINING", "STRONG_DECLINE", "SHARP_DECLINE");

	private static final Set<String> ALLOWED_RISK_LEVELS = Set.of("HIGH", "MEDIUM", "LOW");

	private final GstGrowthRepository repository;

	public List<ReturnPeriodOptionDto> getPeriods() {

		log.info("Fetching GST growth return periods");

		List<ReturnPeriodOptionDto> result = repository.findReturnPeriods();

		log.info("Fetched GST growth return periods count={}", result.size());

		return result;
	}

	public List<OfficeOptionResponse> getOffices(String period) {

		String validPeriod = validatePeriod(period);

		log.debug("Fetching GST growth offices period={}", validPeriod);

		return repository.findOffices(validPeriod);
	}

	public GrowthSummaryResponse getSummary(String period, String office) {

		String validPeriod = validatePeriod(period);

		String normalizedOffice = normalize(office);

		log.debug("Fetching GST growth summary period={} office={}", validPeriod, safeLog(normalizedOffice));

		return repository.findSummary(validPeriod, normalizedOffice);
	}

	public List<GrowthTrendResponse> getTrend(String period, String office) {

		String validPeriod = validatePeriod(period);

		String normalizedOffice = normalize(office);

		log.debug("Fetching GST growth trend period={} office={}", validPeriod, safeLog(normalizedOffice));

		return repository.findTrend(validPeriod, normalizedOffice);
	}

	public PageResponse<GrowthRowResponse> getTaxpayers(String period, String office, String search, String trend,
			String riskLevel, int page, int size) {

		validatePagination(page, size);

		String validPeriod = validatePeriod(period);

		String normalizedOffice = normalize(office);

		String normalizedSearch = normalizeSearch(search);

		String normalizedTrend = normalizeTrend(trend);

		String normalizedRisk = normalizeRisk(riskLevel);

		log.debug(
				"Fetching GST growth taxpayers period={} office={} search={} trend={} risk={} page={} size={}",
				validPeriod, safeLog(normalizedOffice), safeLog(normalizedSearch), safeLog(normalizedTrend),
				safeLog(normalizedRisk), page, size);

		return repository.findGrowthRows(validPeriod, normalizedOffice, normalizedSearch, normalizedTrend,
				normalizedRisk, page, size);
	}

	/**
	 * Full CSV export, optionally scoped by risk level.
	 */
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
				safeLog(normalizedOffice), safeLog(normalizedSearch), safeLog(normalizedTrend), safeLog(normalizedRisk));

		repository.exportCsv(validPeriod, normalizedOffice, normalizedSearch, normalizedTrend, normalizedRisk, writer);
	}

	/**
	 * CSV export without a risk-level filter. Delegates to the repository's
	 * exportGrowthCsv, passing riskLevel as null so every risk level is
	 * included.
	 *
	 * FIX: previously called repository.exportGrowthCsv with 5 arguments
	 * against its 6-arg signature (period, office, search, trend, riskLevel,
	 * writer), which would not compile. It also discarded the result of
	 * validatePeriod(period) and passed the raw, un-normalized period through
	 * to the repository instead. Both are fixed below, and a null writer is
	 * now rejected the same way the risk-filtered export rejects it.
	 *
	 * NOTE: since the repository's exportGrowthCsv now accepts riskLevel, this
	 * overload is functionally just exportCsv(..., riskLevel: null, ...). You
	 * may want to drop it and have callers use the 6-arg exportCsv above with
	 * riskLevel=null instead, rather than maintaining two near-identical entry
	 * points.
	 */
	public void exportCsv(String period, String office, String search, String trend, Writer writer) {

		if (writer == null) {

			log.warn("GST growth CSV export (no-risk variant) rejected: writer is required (period={})",
					safeLog(period));

			throw new InvalidGrowthRequestException("CSV writer is required");
		}

		String validPeriod = validatePeriod(period);

		String normalizedOffice = normalize(office);

		String normalizedSearch = normalizeSearch(search);

		String normalizedTrend = normalizeTrend(trend);

		log.info("Starting GST growth CSV export (no-risk variant) period={} office={} search={} trend={}",
				validPeriod, safeLog(normalizedOffice), safeLog(normalizedSearch), safeLog(normalizedTrend));

		repository.exportGrowthCsv(validPeriod, normalizedOffice, normalizedSearch, normalizedTrend, null, writer);
	}

	private String validatePeriod(String period) {

		String value = normalize(period);

		if (value == null || !value.matches("(0[1-9]|1[0-2])\\d{4}")) {

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
	 * Strips CR/LF/TAB from values before they go into a log line, so a
	 * user-supplied search/office/trend value can't forge extra log entries
	 * (log injection).
	 */
	private String safeLog(String value) {

		if (value == null) {
			return "-";
		}

		return value.replaceAll("[\\r\\n\\t]", "_");
	}
}