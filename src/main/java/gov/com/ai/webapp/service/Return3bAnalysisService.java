package gov.com.ai.webapp.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import gov.com.ai.webapp.exception.GstAnalyticsException;
import gov.com.ai.webapp.model.DashboardDTOs.ComplianceAlertDTO;
import gov.com.ai.webapp.model.DashboardDTOs.GstinAnalysisResponse;
import gov.com.ai.webapp.model.DashboardDTOs.Gstr3bMonthlyReturnDTO;
import gov.com.ai.webapp.model.DealerMaster;
import gov.com.ai.webapp.model.Return3BSummaryBean;
import gov.com.ai.webapp.repository.Return3bRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Return 3B Analysis Service - Aggregates GSTR-3B historical data and evaluates
 * compliance alerts.
 * 
 * Integration Points: - Fetches historical returns from Return3bRepository -
 * Calls GstReturn3bRiskAssessmentService to detect risks and generate alerts -
 * Aggregates metrics across multiple periods - Builds comprehensive GSTIN
 * analysis response
 * 
 * Alert Generation: - Only risk-based alerts from
 * GstReturn3bRiskAssessmentService - Alert de-duplication via Set<String>
 * alertKeys - Per-period error handling (skip failing periods, continue
 * processing)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Return3bAnalysisService {

	/* ========== CONFIGURABLE THRESHOLDS ========== */
	@Value("${gstin.analysis.lookback-months:24}")
	private int historicalMonthsLookback;

	@Value("${gstin.analysis.risk.high-threshold:0.75}")
	private double riskHighThreshold;

	@Value("${gstin.analysis.risk.medium-threshold:0.40}")
	private double riskMediumThreshold;

	/* ========== CONSTANTS ========== */
	private static final Pattern GSTIN_PATTERN = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
	private static final BigDecimal ZERO = BigDecimal.ZERO;
	private static final String GSTIN_NOT_FOUND = "GSTIN profile not found in master record";

	private final Return3bRepository return3bRepository;
	private final GstReturn3bRiskAssessmentService riskAssessmentService;
	private final XgbRiskScoringService xgbService;

	// ============== MAIN SERVICE
	@Transactional(readOnly = true)
	public GstinAnalysisResponse getGstinAnalysis(String gstin) {
		String normalizedGstin = normalizeGstin(gstin);
		validateGstin(normalizedGstin);

		log.info("Starting GSTIN analysis. gstin={}, lookbackMonths={}", normalizedGstin, historicalMonthsLookback);

		try {
			// 1. Fetch Taxpayer Profile
			DealerMaster profile = return3bRepository.findByGstin(normalizedGstin).orElse(null);

			if (profile == null) {
				log.warn("{} for gstin={}", GSTIN_NOT_FOUND, normalizedGstin);
			}

			// 2. Fetch Historical Returns (with null safety)
			List<Return3BSummaryBean> historyBeans = Optional
					.ofNullable(return3bRepository.findHistory(normalizedGstin, historicalMonthsLookback))
					.orElse(new ArrayList<>());
			log.info("Size:--"+historyBeans.size());
			if (historyBeans.isEmpty()) {
				log.warn("No historical returns found for gstin={}", normalizedGstin);
			}

			// 3. Aggregate Metrics
			AggregatedMetrics metrics = aggregateMetrics(historyBeans, normalizedGstin);

			// 4. Evaluate Compliance Alerts (risk-based only)
			List<ComplianceAlertDTO> alerts = evaluateAllAlerts(historyBeans);

			// 5. Build Response
			return buildAnalysisResponse(normalizedGstin, profile, metrics, alerts);

		} catch (GstAnalyticsException ex) {
			log.error("GSTIN analysis failed with validation error. gstin={}", normalizedGstin, ex);
			throw ex;
		} catch (Exception ex) {
			log.error("Unexpected error during GSTIN analysis. gstin={}", normalizedGstin, ex);
			throw new GstAnalyticsException("Failed to analyze GSTIN: " + normalizedGstin);
		}
	}

	/*
	 * ============================================================ AGGREGATION
	 * LOGIC ============================================================
	 */

	/**
	 * Aggregate metrics across all historical returns
	 */
	private AggregatedMetrics aggregateMetrics(List<Return3BSummaryBean> historyBeans, String gstin) {
		AggregatedMetrics metrics = new AggregatedMetrics();
		List<Gstr3bMonthlyReturnDTO> monthlyHistory = new ArrayList<>();

		double highestRiskScore = 0.0;
		double latestRiskScore = 0.0;
		boolean latestRiskCaptured = false;

		for (Return3BSummaryBean bean : historyBeans) {
			// Skip null beans
			if (bean == null) {
				log.warn("Null bean encountered in history list. Skipping.");
				continue;
			}

			// Ensure GSTIN is set
			if (bean.getGstin() == null) {
				bean.setGstin(gstin);
			}

			try {
				// Extract and validate fields with safe() wrapper
				BigDecimal taxableValue = safe(bean.getTaxableValue());
				BigDecimal igst = safe(bean.getOutputIgst());
				BigDecimal cgst = safe(bean.getOutputCgst());
				BigDecimal sgst = safe(bean.getOutputSgst());
				BigDecimal cess = safe(bean.getOutputCess());
				BigDecimal outputTax = safe(bean.getTotalOutputTax());
				BigDecimal itcClaimed = safe(bean.getUtilizedItc());
				BigDecimal cashPaid = safe(bean.getCashTaxPaid());
				BigDecimal rcmTax = safe(bean.getRcmTotalTax());
				BigDecimal excessItc = safe(bean.getExcessItc());
				BigDecimal eligibleItc = safe(bean.getEligibleItc());

				// Accumulate Lifetime Metrics
				metrics.lifetimeTaxable = metrics.lifetimeTaxable.add(taxableValue);
				metrics.lifetimeCash = metrics.lifetimeCash.add(cashPaid);
				metrics.lifetimeItc = metrics.lifetimeItc.add(itcClaimed);
				metrics.lifetimeIgst = metrics.lifetimeIgst.add(igst);
				metrics.lifetimeCgst = metrics.lifetimeCgst.add(cgst);
				metrics.lifetimeSgst = metrics.lifetimeSgst.add(sgst);
				metrics.lifetimeCess = metrics.lifetimeCess.add(cess);
				metrics.lifetimeOutputTax = metrics.lifetimeOutputTax.add(outputTax);
				metrics.lifetimeRcm = metrics.lifetimeRcm.add(rcmTax);
				metrics.totalExcessItc = metrics.totalExcessItc.add(excessItc);
				metrics.totalEligibleItc = metrics.totalEligibleItc.add(eligibleItc);

				// Compute Financial Ratios
				double itcRatio = calculateRatio(bean.getItcUtilizationRatio(), outputTax, itcClaimed);
				double cashRatio = calculateRatio(bean.getCashPaymentRatio(), outputTax, cashPaid);

				int filingDelayDays = bean.getFilingDelayDays() != null ? Math.max(bean.getFilingDelayDays(), 0) : 0;
				String filingStatus = determineFilingStatus(bean, filingDelayDays);

				// Evaluate Risk Score
				double scoreVal = fetchRiskScore(bean);
				scoreVal = normalizeRiskScore(scoreVal);

				if (scoreVal > highestRiskScore) {
					highestRiskScore = scoreVal;
				}

				if (!latestRiskCaptured) {
					latestRiskScore = scoreVal;
					latestRiskCaptured = true;
				}

				// Build Monthly DTO
				Gstr3bMonthlyReturnDTO monthlyDto = Gstr3bMonthlyReturnDTO.builder().retPeriod(bean.getRetPeriod())
						.formattedPeriod(formatPeriodCode(bean.getRetPeriod())).taxableValue(taxableValue).igst(igst)
						.cgst(cgst).sgst(sgst).cess(cess).outputTax(outputTax).itcClaimed(itcClaimed)
						.itcEligible(eligibleItc).cashPaid(cashPaid).rcmTax(rcmTax).itcRatio(roundRatio(itcRatio))
						.cashRatio(roundRatio(cashRatio)).filingDelayDays(filingDelayDays).filingStatus(filingStatus)
						.build();

				monthlyHistory.add(monthlyDto);

			} catch (Exception ex) {
				// Per-period error handling - log and continue
				log.warn("Failed to process monthly bean for period={}. Skipping this period.", bean.getRetPeriod(),
						ex);
				continue;
			}
		}

		metrics.historyList = monthlyHistory;
		metrics.currentRiskScore = latestRiskCaptured ? latestRiskScore : highestRiskScore;
		metrics.riskCategory = categorizeRiskScore(metrics.currentRiskScore);

		log.info("Metrics aggregation completed. gstin={}, monthsProcessed={}, finalRiskScore={}, riskCategory={}",
				gstin, monthlyHistory.size(), metrics.currentRiskScore, metrics.riskCategory);

		return metrics;
	}

	/*
	 * ============================================================ ALERT EVALUATION
	 * (RISK-BASED ONLY - NO LEGACY ALERTS)
	 * ============================================================
	 */

	/**
	 * Evaluate alerts from GstReturn3bRiskAssessmentService only
	 * 
	 * De-duplicates alerts using finding code + period as key
	 */
	private List<ComplianceAlertDTO> evaluateAllAlerts(List<Return3BSummaryBean> historyBeans) {
		List<ComplianceAlertDTO> alerts = new ArrayList<>();
		Set<String> alertKeys = new HashSet<>();

		for (Return3BSummaryBean bean : historyBeans) {
			// Skip null beans
			if (bean == null) {
				continue;
			}

			try {
				// Call risk assessment service to get findings-based alerts
				GstReturn3bRiskAssessmentService.RiskAssessmentResult riskResult = riskAssessmentService.assess(bean);

				if (riskResult != null && riskResult.getAlerts() != null) {
					for (ComplianceAlertDTO riskAlert : riskResult.getAlerts()) {
						// De-duplicate: use finding code + period as key
						String alertKey = riskAlert.getFindingCode() + "-" + bean.getRetPeriod();

						if (alertKeys.add(alertKey)) {
							alerts.add(riskAlert);
							log.debug("Added risk-based alert. code={}, period={}, severity={}",
									riskAlert.getFindingCode(), bean.getRetPeriod(), riskAlert.getSeverity());
						} else {
							log.debug("Duplicate alert skipped. key={}", alertKey);
						}
					}
				}

			} catch (Exception ex) {
				log.warn("Failed to evaluate alerts for period={}. Skipping this period.", bean.getRetPeriod(), ex);
				continue;
			}
		}

		log.info("Alert evaluation completed. totalAlerts={}", alerts.size());
		return alerts;
	}

	// ============RESPONSE BUILDING ==============

	private GstinAnalysisResponse buildAnalysisResponse(String gstin, DealerMaster profile, AggregatedMetrics metrics,
			List<ComplianceAlertDTO> alerts) {

		String legalName = Optional.ofNullable(profile).map(DealerMaster::getLegalName).filter(this::hasText)
				.orElse("N/A");

		String tradeName = Optional.ofNullable(profile).map(DealerMaster::getTradeName).filter(this::hasText)
				.orElse("N/A");

		String pan = Optional.ofNullable(profile).map(DealerMaster::getPanNo).filter(this::hasText).orElse("N/A");

		String jurisdiction = Optional.ofNullable(profile).map(DealerMaster::getStJuri).filter(this::hasText)
				.orElse("N/A");

		String status = Optional.ofNullable(profile).map(DealerMaster::getAuthStatus).filter(this::hasText)
				.orElse("ACTIVE");

		return GstinAnalysisResponse.builder().gstin(gstin).legalName(legalName).tradeName(tradeName).pan(pan)
				.jurisdiction(jurisdiction).taxpayerType("REGULAR").status(status)
				.lifetaxableValue(scale2(metrics.lifetimeTaxable)).lifetimeIgst(scale2(metrics.lifetimeIgst))
				.lifetimeCgst(scale2(metrics.lifetimeCgst)).lifetimeSgst(scale2(metrics.lifetimeSgst))
				.lifetimeCess(scale2(metrics.lifetimeCess)).lifetimeOutputTax(scale2(metrics.lifetimeOutputTax))
				.lifetimeRcmTax(scale2(metrics.lifetimeRcm)).lifetimeExcessItc(scale2(metrics.totalExcessItc))
				.lifetimeTaxableValue(scale2(metrics.lifetimeTaxable)).lifetimeCashPaid(scale2(metrics.lifetimeCash))
				.lifetimeItcUtilized(scale2(metrics.lifetimeItc)).lifetimeItcEligible(scale2(metrics.totalEligibleItc))
				.currentRiskScore(roundRatio(metrics.currentRiskScore)).riskCategory(metrics.riskCategory)
				.last6MonthsHistory(metrics.historyList).activeAlerts(alerts).build();
	}

	// =========== UTILITY METHODS

	/**
	 * Fetch risk score from XGB service or fallback to database
	 */
	private double fetchRiskScore(Return3BSummaryBean bean) {
		if (xgbService == null) {
			BigDecimal dbScore = bean.getXgbRiskScore();
			return dbScore != null ? dbScore.doubleValue() : 0.0;
		}

		try {
			BigDecimal predictedScore = xgbService.predictRiskScore(bean);
			if (predictedScore != null) {
				return normalizeRiskScore(predictedScore.doubleValue());
			}
		} catch (Exception ex) {
			log.debug("XGBoost scoring failed for period={}. Falling back to database metric.", bean.getRetPeriod(),
					ex);
		}

		// Fallback to database score
		BigDecimal databaseScore = bean.getXgbRiskScore();
		return databaseScore != null ? normalizeRiskScore(databaseScore.doubleValue()) : 0.0;
	}

	/**
	 * Normalize risk score to 0.0 - 1.0 range
	 */
	private double normalizeRiskScore(double score) {
		if (Double.isNaN(score) || Double.isInfinite(score) || score < 0) {
			log.warn("Invalid risk score detected: {}. Resetting to 0.0", score);
			return 0.0;
		}

		// Convert percentage to decimal if needed (e.g., 85.5 -> 0.855)
		if (score > 1.0 && score <= 100.0) {
			score = score / 100.0;
		}

		// Clamp to [0.0, 1.0]
		return Math.min(Math.max(score, 0.0), 1.0);
	}

	/**
	 * Determine filing status based on date and delay
	 */
	private String determineFilingStatus(Return3BSummaryBean bean, int filingDelayDays) {
		if (bean.getFilingDate() == null) {
			return "PENDING";
		}
		return filingDelayDays > 0 ? "DELAYED" : "FILED";
	}

	/**
	 * Categorize risk score into HIGH/MEDIUM/LOW
	 */
	private String categorizeRiskScore(double score) {
		if (score >= riskHighThreshold) {
			return "HIGH";
		}
		if (score >= riskMediumThreshold) {
			return "MEDIUM";
		}
		return "LOW";
	}

	/**
	 * Format period code from MMYYYY to MM/YYYY
	 */
	private String formatPeriodCode(String retPeriod) {
		if (retPeriod == null || retPeriod.length() != 6) {
			return retPeriod;
		}

		try {
			int month = Integer.parseInt(retPeriod.substring(0, 2));
			String year = retPeriod.substring(2, 6);

			if (month < 1 || month > 12) {
				log.debug("Invalid month in period code: {}", retPeriod);
				return retPeriod;
			}

			return String.format("%02d/%s", month, year);
		} catch (NumberFormatException ex) {
			log.debug("Failed to format period code: {}", retPeriod, ex);
			return retPeriod;
		}
	}

	/**
	 * Safe BigDecimal null handling
	 */
	private BigDecimal safe(BigDecimal value) {
		return value == null ? ZERO : value;
	}

	/**
	 * Calculate ratio safely
	 */
	private double calculateRatio(BigDecimal dbRatio, BigDecimal denominator, BigDecimal numerator) {
		// Prefer database ratio if available
		if (dbRatio != null) {
			double val = dbRatio.setScale(4, RoundingMode.HALF_UP).doubleValue();
			// Handle percentage values stored as 80 instead of 0.80
			if (val > 1.0) {
				val = val / 100.0;
			}
			return clampRatio(val);
		}

		BigDecimal safeDenom = safe(denominator);
		BigDecimal safeNum = safe(numerator);

		// Check division by zero
		if (safeDenom.compareTo(ZERO) > 0) {
			double result = safeNum.divide(safeDenom, 4, RoundingMode.HALF_UP).doubleValue();
			return clampRatio(result);
		}

		return 0.0;
	}

	/**
	 * Clamp ratio to valid range [0.0, 1.0]
	 */
	private double clampRatio(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			log.warn("Invalid ratio detected: {}. Resetting to 0.0", value);
			return 0.0;
		}
		return Math.min(Math.max(value, 0.0), 1.0);
	}

	/**
	 * Round ratio to 4 decimal places
	 */
	private double roundRatio(double value) {
		return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
	}

	/**
	 * Scale BigDecimal to 2 decimal places
	 */
	private BigDecimal scale2(BigDecimal value) {
		return safe(value).setScale(2, RoundingMode.HALF_UP);
	}

	/**
	 * Normalize GSTIN (trim and uppercase)
	 */
	private String normalizeGstin(String gstin) {
		return gstin == null ? "" : gstin.trim().toUpperCase();
	}

	/**
	 * Validate GSTIN format
	 */
	private void validateGstin(String gstin) {
		if (!hasText(gstin)) {
			throw new GstAnalyticsException("GSTIN input parameter cannot be empty or null.");
		}
		if (!GSTIN_PATTERN.matcher(gstin).matches()) {
			throw new GstAnalyticsException("Invalid GSTIN format. Expected: 2-char state code, 5-char PAN alphabet, "
					+ "4-char entity number, 1-char entity type, 1-char subsidiary, Z, 1-char checksum. Provided: "
					+ gstin);
		}
	}

	/**
	 * Check if string has text
	 */
	private boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}

	// ====================== INNER CLASS:* AGGREGATED METRICS

	/**
	 * Container for aggregated metrics across all historical returns
	 */
	private static class AggregatedMetrics {
		BigDecimal lifetimeTaxable = ZERO;
		BigDecimal lifetimeCash = ZERO;
		BigDecimal lifetimeItc = ZERO;
		BigDecimal lifetimeIgst = ZERO;
		BigDecimal lifetimeCgst = ZERO;
		BigDecimal lifetimeSgst = ZERO;
		BigDecimal lifetimeCess = ZERO;
		BigDecimal lifetimeOutputTax = ZERO;
		BigDecimal lifetimeRcm = ZERO;
		BigDecimal totalExcessItc = ZERO;
		BigDecimal totalEligibleItc = ZERO;

		List<Gstr3bMonthlyReturnDTO> historyList = new ArrayList<>();
		double currentRiskScore = 0.0;
		String riskCategory = "LOW";
	}
}