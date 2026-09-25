package gov.com.ai.webapp.service.dto;

import gov.com.ai.webapp.exception.GstAnalyticsException;
import gov.com.ai.webapp.exception.GstDataNotFoundException;
import gov.com.ai.webapp.exception.GstValidationException;
import gov.com.ai.webapp.model.dto.GstItcRiskRow;
import gov.com.ai.webapp.repository.dto.GstItcBulkRepository;
import gov.com.ai.webapp.repository.dto.GstItcBulkRepository.GstItcDbRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class GstItcBulkAnalyticsService {

	public static final String CACHE_NAME = "gstItcAnalyticsCache";

	private final GstItcBulkRepository repository;
	private final GstItcCalculationEngine calculationEngine;
	private final GstItcRiskScoringEngine riskScoringEngine;
	private final GstCombinedRiskEngine combinedRiskEngine;
	private final Executor taskExecutor; 

	/**
	 * Executes bulk analytics with caching support. Caches results per return
	 * period (MMYYYY).
	 */
	@Transactional(readOnly = true)
	@Cacheable(value = CACHE_NAME, key = "#retPeriod", unless = "#result == null || #result.isEmpty()")
	public List<GstItcRiskRow> analyze(String retPeriod) {

		log.info("Processing analytics request (Cache Miss). retPeriod={}", retPeriod);

		validatePeriod(retPeriod);

		List<GstItcDbRow> rows;
		try {
			rows = repository.findByReturnPeriod(retPeriod);
		} catch (DataAccessException ex) {
			log.error("Database failure during bulk ITC fetch. retPeriod={}, rootCause={}", retPeriod,
					getRootCauseMessage(ex), ex);
			throw new GstAnalyticsException("Database execution failed for return period " + retPeriod);
		}

		if (rows == null || rows.isEmpty()) {
			log.warn("No ITC records found for execution. retPeriod={}", retPeriod);
			throw new GstDataNotFoundException("No ITC records located for return period: " + retPeriod);
		}

		log.info("Starting high-throughput analysis. retPeriod={}, totalRecords={}", retPeriod, rows.size());

		return processRowsConcurrently(rows, retPeriod);
	}

	/**
	 * Manually evicts the cache for a specific return period (e.g., after batch
	 * re-processing).
	 */
	@CacheEvict(value = CACHE_NAME, key = "#retPeriod")
	public void evictAnalyticsCache(String retPeriod) {
		log.info("Evicting ITC analytics cache for retPeriod={}", retPeriod);
	}

	/**
	 * Parallel row processing engine utilizing CompletableFuture for
	 * chunk-based/concurrent scaling.
	 */
	private List<GstItcRiskRow> processRowsConcurrently(List<GstItcDbRow> rows, String retPeriod) {
		AtomicLong serialSequence = new AtomicLong(1L);
		AtomicLong successCounter = new AtomicLong(0L);
		AtomicLong failureCounter = new AtomicLong(0L);

		List<CompletableFuture<GstItcRiskRow>> futures = rows.stream().filter(Objects::nonNull)
				.map(row -> CompletableFuture.supplyAsync(() -> {
					try {
						long currentSerial = serialSequence.getAndIncrement();
						GstItcRiskRow riskRow = analyzeRow(row, currentSerial);
						successCounter.incrementAndGet();
						return riskRow;
					} catch (Exception ex) {
						failureCounter.incrementAndGet();
						log.error("Failed processing record. gstin={}, retPeriod={}, rootCause={}", row.gstin(),
								row.retPeriod(), getRootCauseMessage(ex), ex);
						return null; // Fault-tolerant skip
					}
				}, taskExecutor)).toList();

		// Join non-null results
		List<GstItcRiskRow> result = futures.stream().map(CompletableFuture::join).filter(Objects::nonNull)
				.collect(Collectors.toList());

		log.info("Bulk ITC analytics batch complete. retPeriod={}, submitted={}, succeeded={}, failed={}", retPeriod,
				rows.size(), successCounter.get(), failureCounter.get());

		return Collections.unmodifiableList(result);
	}

	/**
	 * Business execution per row entity.
	 */
	private GstItcRiskRow analyzeRow(GstItcDbRow row, long serial) {
		GstItcCalculationEngine.Calculation calculation = calculationEngine.calculate(row);

		int historicalCount = 0; // Reserved for 6/12 month historical engine

		BigDecimal ruleScore = safe(riskScoringEngine.calculateRuleScore(safe(calculation.potentialExcessItc()),
				safe(calculation.utilizationPercent()), historicalCount));

		BigDecimal xgbScore = safe(row.xgbRiskScore());
		BigDecimal dl4jScore = safe(row.dl4jAnomalyScore());

		GstCombinedRiskEngine.Result risk = combinedRiskEngine.calculate(xgbScore, dl4jScore, ruleScore);

		BigDecimal rcmTotalItc = calculateRcmItc(row);

		return new GstItcRiskRow(serial, row.gstin(), row.retPeriod(), safe(row.taxableValue()),
				safe(row.totalOutputTax()), safe(calculation.eligibleItc()), safe(calculation.reversedItc()),
				safe(calculation.ineligibleItc()), safe(calculation.utilizedItc()), safe(calculation.netEligibleItc()),
				safe(calculation.potentialExcessItc()), safe(calculation.utilizationPercent()),
				safe(calculation.itcToTaxPercent()), safe(row.rcmTotalTax()), rcmTotalItc, xgbScore, dl4jScore,
				ruleScore, safe(risk.finalScore()), risk.category(), calculation.status(), historicalCount,
				buildReason(calculation, historicalCount));
	}

	private BigDecimal calculateRcmItc(GstItcDbRow row) {
		return safe(row.rcmItcIgst()).add(safe(row.rcmItcCgst())).add(safe(row.rcmItcSgst()))
				.add(safe(row.rcmItcCess()));
	}

	private String buildReason(GstItcCalculationEngine.Calculation calculation, int historicalCount) {
		List<String> reasons = new ArrayList<>(4);

		if (safe(calculation.potentialExcessItc()).signum() > 0) {
			reasons.add("Potential ITC utilization exceeding calculated net eligible ITC");
		}
		if (safe(calculation.reversedItc()).signum() > 0) {
			reasons.add("ITC reversal reported");
		}
		if (safe(calculation.ineligibleItc()).signum() > 0) {
			reasons.add("Ineligible ITC reported");
		}
		if (historicalCount >= 3) {
			reasons.add("Repeated ITC discrepancy in historical periods");
		}

		return reasons.isEmpty() ? "No material ITC discrepancy identified" : String.join("; ", reasons);
	}

	private void validatePeriod(String retPeriod) {
		if (retPeriod == null || !retPeriod.matches("^(0[1-9]|1[0-2])\\d{4}$")) {
			throw new GstValidationException("Invalid return period format: " + retPeriod + ". Expected MMYYYY.");
		}
	}

	private static BigDecimal safe(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

	private static String getRootCauseMessage(Throwable throwable) {
		Throwable root = throwable;
		while (root.getCause() != null) {
			root = root.getCause();
		}
		return root.getMessage();
	}
}