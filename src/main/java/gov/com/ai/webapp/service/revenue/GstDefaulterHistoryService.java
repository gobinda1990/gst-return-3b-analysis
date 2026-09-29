package gov.com.ai.webapp.service.revenue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gov.com.ai.webapp.model.revenue.DefaulterHistoryResponse;
import gov.com.ai.webapp.repository.revenue.GstDefaulterHistoryRepository;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class GstDefaulterHistoryService {

	private static final int DEFAULT_MONTHS = 12;
	private static final int MAX_MONTHS = 60;

	private static final Pattern GSTIN_PATTERN = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");

	private final GstDefaulterHistoryRepository repository;

	@Transactional(readOnly = true)
	public List<DefaulterHistoryResponse> getHistory(String gstin, Integer months) {

		String normalizedGstin = normalizeAndValidateGstin(gstin);

		int requestedMonths = validateMonths(months);

		log.info("Fetching defaulter return history gstin={}, months={}", normalizedGstin, requestedMonths);

		return repository.findHistory(normalizedGstin, requestedMonths);
	}

	private String normalizeAndValidateGstin(String gstin) {

		if (gstin == null || gstin.isBlank()) {

			throw new IllegalArgumentException("GSTIN is required.");
		}

		String normalized = gstin.trim().toUpperCase(Locale.ROOT);

		if (!GSTIN_PATTERN.matcher(normalized).matches()) {

			throw new IllegalArgumentException("Invalid GSTIN format: " + normalized);
		}

		return normalized;
	}

	private int validateMonths(Integer months) {

		if (months == null) {
			return DEFAULT_MONTHS;
		}

		if (months < 1 || months > MAX_MONTHS) {

			throw new IllegalArgumentException("months must be between 1 and " + MAX_MONTHS);
		}

		return months;
	}
}
