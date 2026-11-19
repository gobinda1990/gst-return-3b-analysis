
package gov.com.ai.webapp.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
 * Production-oriented GSTR-3B Analysis Service.
 *
 * Responsibilities: 1. Fetch taxpayer profile and historical GSTR-3B returns.
 * 2. Aggregate historical tax and ITC amounts. 3. Calculate monthly financial
 * ratios from source amounts. 4. Generate risk-based compliance alerts. 5.
 * Calculate XGBoost risk scores with database fallback. 6. Build GSTIN analysis
 * response.
 *
 * Ratio definitions:
 *
 * ITC_RATIO = UTILIZED_ITC / OUTPUT_TAX
 *
 * ITC_UTILIZATION_RATIO = UTILIZED_ITC / ELIGIBLE_ITC
 *
 * ITC_TO_TAX_RATIO = ELIGIBLE_ITC / OUTPUT_TAX
 *
 * CASH_PAYMENT_RATIO = CASH_TAX_PAID / (OUTPUT_TAX + RCM_TAX)
 *
 * IMPORTANT: cashTaxPaid must include cash paid against RCM liability for the
 * CASH_PAYMENT_RATIO formula above.
 *
 * All financial ratios are decimal values: 0.500000 = 50%
 *
 * Financial ratios are not restricted to 0.0 - 1.0. In particular,
 * ITC_TO_TAX_RATIO can exceed 1.0.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Return3bAnalysisService {

	// =========================================================
	// CONFIGURATION
	// =========================================================

	@Value("${gstin.analysis.lookback-months:24}")
	private int historicalMonthsLookback;

	@Value("${gstin.analysis.risk.high-threshold:0.75}")
	private double riskHighThreshold;

	@Value("${gstin.analysis.risk.medium-threshold:0.40}")
	private double riskMediumThreshold;

	// =========================================================
	// CONSTANTS
	// =========================================================

	private static final Pattern GSTIN_PATTERN = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");

	private static final BigDecimal ZERO = BigDecimal.ZERO;

	private static final int RATIO_SCALE = 6;

	private static final int MONEY_SCALE = 2;

	private static final String GSTIN_NOT_FOUND = "GSTIN profile not found in master record";

	private static final DateTimeFormatter DISPLAY_PERIOD_FORMAT = DateTimeFormatter.ofPattern("MMM yyyy",
			Locale.ENGLISH);

	// =========================================================
	// DEPENDENCIES
	// =========================================================

	private final Return3bRepository return3bRepository;

	private final GstReturn3bRiskAssessmentService riskAssessmentService;

	private final XgbRiskScoringService xgbService;

	// =========================================================
	// MAIN ANALYSIS
	// =========================================================

	@Transactional(readOnly = true)
	public GstinAnalysisResponse getGstinAnalysis(String gstin) {

		String normalizedGstin = normalizeGstin(gstin);

		validateGstin(normalizedGstin);

		log.info("Starting GSTIN analysis. gstin={}, lookbackMonths={}", normalizedGstin, historicalMonthsLookback);

		try {

			// -------------------------------------------------
			// 1. Fetch taxpayer profile
			// -------------------------------------------------

			DealerMaster profile = return3bRepository.findByGstin(normalizedGstin).orElse(null);

			if (profile == null) {
				log.warn("{}. gstin={}", GSTIN_NOT_FOUND, normalizedGstin);
			}

			// -------------------------------------------------
			// 2. Fetch historical returns
			// -------------------------------------------------

			List<Return3BSummaryBean> historyBeans = Optional
					.ofNullable(return3bRepository.findHistory(normalizedGstin, historicalMonthsLookback))
					.orElseGet(ArrayList::new);

			log.info("Historical return records fetched. gstin={}, count={}", normalizedGstin, historyBeans.size());

			if (historyBeans.isEmpty()) {
				log.warn("No historical returns found. gstin={}", normalizedGstin);
			}

			// -------------------------------------------------
			// 3. Aggregate financial and risk metrics
			// -------------------------------------------------

			AggregatedMetrics metrics = aggregateMetrics(historyBeans, normalizedGstin);

			// -------------------------------------------------
			// 4. Evaluate compliance alerts
			// -------------------------------------------------

			List<ComplianceAlertDTO> alerts = evaluateAllAlerts(historyBeans);

			// -------------------------------------------------
			// 5. Build final response
			// -------------------------------------------------

			return buildAnalysisResponse(normalizedGstin, profile, metrics, alerts);

		} catch (GstAnalyticsException ex) {

			log.error("GSTIN analysis validation failed. gstin={}", normalizedGstin, ex);

			throw ex;

		} catch (Exception ex) {

			log.error("Unexpected error during GSTIN analysis. gstin={}", normalizedGstin, ex);

			throw new GstAnalyticsException("Failed to analyze GSTIN: " + normalizedGstin);
		}
	}

	// =========================================================
	// AGGREGATION LOGIC
	// =========================================================

	/**
	 * Aggregate historical returns and calculate monthly ratios.
	 *
	 * Source monetary values are authoritative. Existing database ratio columns are
	 * deliberately not trusted.
	 */
	private AggregatedMetrics aggregateMetrics(List<Return3BSummaryBean> historyBeans, String gstin) {

		AggregatedMetrics metrics = new AggregatedMetrics();

		List<Gstr3bMonthlyReturnDTO> monthlyHistory = new ArrayList<>();

		double highestRiskScore = 0.0;

		double latestRiskScore = 0.0;

		YearMonth latestRiskPeriod = null;

		boolean latestRiskCaptured = false;

		if (historyBeans == null || historyBeans.isEmpty()) {

			metrics.historyList = monthlyHistory;

			metrics.currentRiskScore = 0.0;

			metrics.riskCategory = categorizeRiskScore(0.0);

			return metrics;
		}

		for (Return3BSummaryBean bean : historyBeans) {

			// -------------------------------------------------
			// Null record protection
			// -------------------------------------------------

			if (bean == null) {

				log.warn("Null historical return encountered. gstin={}", gstin);

				continue;
			}

			try {

				// -------------------------------------------------
				// 1. Read source financial amounts
				// -------------------------------------------------

				BigDecimal taxableValue = safe(bean.getTaxableValue());

				BigDecimal igst = safe(bean.getOutputIgst());

				BigDecimal cgst = safe(bean.getOutputCgst());

				BigDecimal sgst = safe(bean.getOutputSgst());

				BigDecimal cess = safe(bean.getOutputCess());

				/*
				 * Normal output tax must exclude RCM. RCM liability is maintained separately.
				 */
				BigDecimal outputTax = safe(bean.getTotalOutputTax());

				/*
				 * Utilized ITC is used for ITC payment ratios.
				 */
				BigDecimal itcClaimed = safe(bean.getUtilizedItc());

				BigDecimal eligibleItc = safe(bean.getEligibleItc());

				/*
				 * Cash tax paid is assumed to include RCM cash payment.
				 */
				BigDecimal cashPaid = safe(bean.getCashTaxPaid());

				BigDecimal rcmTax = safe(bean.getRcmTotalTax());

				BigDecimal excessItc = safe(bean.getExcessItc());

				// -------------------------------------------------
				// 2. Aggregate lifetime financial metrics
				// -------------------------------------------------

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

				// -------------------------------------------------
				// 3. Calculate financial ratios
				// -------------------------------------------------

				/*
				 * ITC ratio:
				 *
				 * Utilized ITC / Normal output tax
				 */
				BigDecimal itcRatio = calculateBigDecimalRatio(itcClaimed, outputTax);

				/*
				 * ITC utilization ratio:
				 *
				 * Utilized ITC / Eligible ITC
				 */
				BigDecimal itcUtilizationRatio = calculateBigDecimalRatio(itcClaimed, eligibleItc);

				/*
				 * ITC-to-tax ratio:
				 *
				 * Eligible ITC / Normal output tax
				 *
				 * This ratio can legitimately exceed 1.0.
				 */
				BigDecimal itcToTaxRatio = calculateBigDecimalRatio(eligibleItc, outputTax);

				/*
				 * Cash payment ratio:
				 *
				 * Cash paid / Total tax liability
				 *
				 * Total tax liability includes normal output tax and RCM tax under the stated
				 * data assumption.
				 */
				BigDecimal totalTaxLiability = outputTax.add(rcmTax);

				BigDecimal cashPaymentRatio = calculateBigDecimalRatio(cashPaid, totalTaxLiability);

				BigDecimal cashRatio = cashPaymentRatio;

				// -------------------------------------------------
				// 4. Filing information
				// -------------------------------------------------

				int filingDelayDays = bean.getFilingDelayDays() != null ? Math.max(bean.getFilingDelayDays(), 0) : 0;

				String filingStatus = determineFilingStatus(bean, filingDelayDays);

				// -------------------------------------------------
				// 5. XGBoost risk score
				// -------------------------------------------------

				double scoreVal = fetchRiskScore(bean);

				scoreVal = normalizeRiskScore(scoreVal);

				highestRiskScore = Math.max(highestRiskScore, scoreVal);

				// -------------------------------------------------
				// 6. Identify latest period correctly
				// -------------------------------------------------

				YearMonth currentPeriod = parseReturnPeriod(bean.getRetPeriod());

				if (currentPeriod != null) {

					if (latestRiskPeriod == null || currentPeriod.isAfter(latestRiskPeriod)) {

						latestRiskPeriod = currentPeriod;

						latestRiskScore = scoreVal;

						latestRiskCaptured = true;
					}

				} else if (!latestRiskCaptured) {

					/*
					 * Fallback for invalid or missing period codes. The first valid processed
					 * record is retained until a valid period is found.
					 */
					latestRiskScore = scoreVal;

					latestRiskCaptured = true;
				}

				// -------------------------------------------------
				// 7. Build monthly DTO
				// -------------------------------------------------

				Gstr3bMonthlyReturnDTO monthlyDto = Gstr3bMonthlyReturnDTO.builder()

						.retPeriod(bean.getRetPeriod())

						.formattedPeriod(formatPeriodCode(bean.getRetPeriod()))

						.taxableValue(taxableValue)

						.igst(igst)

						.cgst(cgst)

						.sgst(sgst)

						.cess(cess)

						.outputTax(outputTax)

						.itcClaimed(itcClaimed)

						.itcEligible(eligibleItc)

						.cashPaid(cashPaid)

						.rcmTax(rcmTax)

						/*
						 * Double fields in DTO. BigDecimal ratios are converted only at the DTO
						 * boundary.
						 */
						.itcRatio(roundRatio(itcRatio.doubleValue()))

						.cashRatio(roundRatio(cashRatio.doubleValue()))

						.filingDelayDays(filingDelayDays)

						.filingStatus(filingStatus)

						/*
						 * BigDecimal ratio fields.
						 */
						.itcUtilizationRatio(itcUtilizationRatio)

						.itcToTaxRatio(itcToTaxRatio)

						.cashPaymentRatio(cashPaymentRatio)

						.build();

				monthlyHistory.add(monthlyDto);

			} catch (Exception ex) {

				/*
				 * A bad historical record must not stop processing of other return periods.
				 */
				log.error("Failed to process monthly return. gstin={}, period={}. Skipping record.", gstin,
						bean.getRetPeriod(), ex);
			}
		}

		// -----------------------------------------------------
		// Final aggregation
		// -----------------------------------------------------

		metrics.historyList = monthlyHistory;

		metrics.currentRiskScore = latestRiskCaptured ? latestRiskScore : highestRiskScore;

		metrics.riskCategory = categorizeRiskScore(metrics.currentRiskScore);

		log.info(
				"Metrics aggregation completed. gstin={}, monthsProcessed={}, "
						+ "outputTax={}, utilizedITC={}, eligibleITC={}, "
						+ "cashPaid={}, rcmTax={}, latestRiskScore={}, riskCategory={}",
				gstin, monthlyHistory.size(), metrics.lifetimeOutputTax, metrics.lifetimeItc, metrics.totalEligibleItc,
				metrics.lifetimeCash, metrics.lifetimeRcm, metrics.currentRiskScore, metrics.riskCategory);

		return metrics;
	}

	// =========================================================
	// COMPLIANCE ALERT EVALUATION
	// =========================================================

	/**
	 * Evaluate risk-based alerts for historical returns.
	 *
	 * Duplicate alerts are removed using finding code + period.
	 */
	private List<ComplianceAlertDTO> evaluateAllAlerts(List<Return3BSummaryBean> historyBeans) {

		List<ComplianceAlertDTO> alerts = new ArrayList<>();

		Set<String> alertKeys = new HashSet<>();

		if (historyBeans == null || historyBeans.isEmpty()) {
			return alerts;
		}

		for (Return3BSummaryBean bean : historyBeans) {

			if (bean == null) {
				continue;
			}

			try {

				GstReturn3bRiskAssessmentService.RiskAssessmentResult riskResult = riskAssessmentService.assess(bean);

				if (riskResult == null || riskResult.getAlerts() == null) {
					continue;
				}

				for (ComplianceAlertDTO riskAlert : riskResult.getAlerts()) {

					if (riskAlert == null) {
						continue;
					}

					String findingCode = riskAlert.getFindingCode();

					if (findingCode == null || findingCode.trim().isEmpty()) {

						log.warn("Skipping alert with empty finding code. period={}", bean.getRetPeriod());

						continue;
					}

					String alertKey = findingCode + "-" + String.valueOf(bean.getRetPeriod());

					if (alertKeys.add(alertKey)) {

						alerts.add(riskAlert);

						log.debug("Added risk alert. code={}, period={}, severity={}", findingCode, bean.getRetPeriod(),
								riskAlert.getSeverity());

					} else {

						log.debug("Duplicate alert skipped. key={}", alertKey);
					}
				}

			} catch (Exception ex) {

				log.error("Risk alert evaluation failed. period={}. Continuing.", bean.getRetPeriod(), ex);
			}
		}

		log.info("Alert evaluation completed. totalAlerts={}", alerts.size());

		return alerts;
	}

	// =========================================================
	// RESPONSE BUILDING
	// =========================================================

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

		return GstinAnalysisResponse.builder()

				.gstin(gstin)

				.legalName(legalName)

				.tradeName(tradeName)

				.pan(pan)

				.jurisdiction(jurisdiction)

				.taxpayerType("REGULAR")

				.status(status)

				.lifetaxableValue(scale2(metrics.lifetimeTaxable))

				.lifetimeTaxableValue(scale2(metrics.lifetimeTaxable))

				.lifetimeIgst(scale2(metrics.lifetimeIgst))

				.lifetimeCgst(scale2(metrics.lifetimeCgst))

				.lifetimeSgst(scale2(metrics.lifetimeSgst))

				.lifetimeCess(scale2(metrics.lifetimeCess))

				.lifetimeOutputTax(scale2(metrics.lifetimeOutputTax))

				.lifetimeRcmTax(scale2(metrics.lifetimeRcm))

				.lifetimeExcessItc(scale2(metrics.totalExcessItc))

				.lifetimeCashPaid(scale2(metrics.lifetimeCash))

				.lifetimeItcUtilized(scale2(metrics.lifetimeItc))

				.lifetimeItcEligible(scale2(metrics.totalEligibleItc))

				.currentRiskScore(roundRatio(metrics.currentRiskScore))

				.riskCategory(metrics.riskCategory)

				.last6MonthsHistory(metrics.historyList)

				.activeAlerts(alerts)

				.build();
	}

	// =========================================================
	// FINANCIAL RATIO UTILITIES
	// =========================================================

	/**
	 * Calculate financial ratio safely.
	 *
	 * Formula: numerator / denominator
	 *
	 * Returns decimal ratio: 0.500000 = 50%
	 *
	 * No upper limit is applied. This is important for ITC-to-tax ratios.
	 */
	private BigDecimal calculateBigDecimalRatio(BigDecimal numerator, BigDecimal denominator) {

		BigDecimal safeNumerator = safe(numerator);

		BigDecimal safeDenominator = safe(denominator);

		if (safeDenominator.compareTo(ZERO) <= 0) {

			return ZERO.setScale(RATIO_SCALE, RoundingMode.HALF_UP);
		}

		if (safeNumerator.compareTo(ZERO) < 0) {

			log.warn("Negative financial ratio numerator. numerator={}, denominator={}", safeNumerator,
					safeDenominator);

			return ZERO.setScale(RATIO_SCALE, RoundingMode.HALF_UP);
		}

		return safeNumerator.divide(safeDenominator, RATIO_SCALE, RoundingMode.HALF_UP);
	}

	// =========================================================
	// XGBOOST RISK SCORING
	// =========================================================

	/**
	 * Fetch risk score from XGBoost service.
	 *
	 * If prediction fails or returns null, fall back to the database risk score.
	 */
	private double fetchRiskScore(Return3BSummaryBean bean) {

		if (bean == null) {
			return 0.0;
		}

		if (xgbService == null) {

			BigDecimal databaseScore = bean.getXgbRiskScore();

			return databaseScore != null ? normalizeRiskScore(databaseScore.doubleValue()) : 0.0;
		}

		try {

			BigDecimal predictedScore = xgbService.predictRiskScore(bean);

			if (predictedScore != null) {

				return normalizeRiskScore(predictedScore.doubleValue());
			}

		} catch (Exception ex) {

			log.warn("XGBoost prediction failed for period={}. " + "Using database risk score.", bean.getRetPeriod(),
					ex);
		}

		// Database fallback
		BigDecimal databaseScore = bean.getXgbRiskScore();

		return databaseScore != null ? normalizeRiskScore(databaseScore.doubleValue()) : 0.0;
	}

	/**
	 * Normalize risk score to 0.0 - 1.0.
	 *
	 * Database percentage scores such as 85.5 are converted to 0.855.
	 */
	private double normalizeRiskScore(double score) {

		if (Double.isNaN(score) || Double.isInfinite(score) || score < 0.0) {

			log.warn("Invalid risk score detected: {}. Resetting to zero.", score);

			return 0.0;
		}

		if (score > 1.0 && score <= 100.0) {
			score = score / 100.0;
		}

		return Math.min(Math.max(score, 0.0), 1.0);
	}

	// =========================================================
	// FILING STATUS
	// =========================================================

	private String determineFilingStatus(Return3BSummaryBean bean, int filingDelayDays) {

		if (bean == null || bean.getFilingDate() == null) {
			return "PENDING";
		}

		return filingDelayDays > 0 ? "DELAYED" : "FILED";
	}

	// =========================================================
	// RISK CATEGORY
	// =========================================================

	private String categorizeRiskScore(double score) {

		if (Double.isNaN(score) || Double.isInfinite(score) || score < 0.0) {

			return "LOW";
		}

		if (score >= riskHighThreshold) {
			return "HIGH";
		}

		if (score >= riskMediumThreshold) {
			return "MEDIUM";
		}

		return "LOW";
	}

	// =========================================================
	// RETURN PERIOD UTILITIES
	// =========================================================

	/**
	 * Convert MMYYYY to MMM yyyy.
	 *
	 * Example: 032026 -> Mar 2026
	 */
	private String formatPeriodCode(String retPeriod) {

		YearMonth yearMonth = parseReturnPeriod(retPeriod);

		if (yearMonth == null) {
			return retPeriod;
		}

		return yearMonth.format(DISPLAY_PERIOD_FORMAT);
	}

	/**
	 * Parse GSTR-3B return period MMYYYY.
	 */
	private YearMonth parseReturnPeriod(String retPeriod) {

		if (retPeriod == null || !retPeriod.matches("\\d{6}")) {
			return null;
		}

		try {

			int month = Integer.parseInt(retPeriod.substring(0, 2));

			int year = Integer.parseInt(retPeriod.substring(2, 6));

			if (month < 1 || month > 12) {
				return null;
			}

			return YearMonth.of(year, month);

		} catch (Exception ex) {

			log.debug("Invalid return period: {}", retPeriod);

			return null;
		}
	}

	// =========================================================
	// GENERAL UTILITIES
	// =========================================================

	/**
	 * Null-safe BigDecimal.
	 */
	private BigDecimal safe(BigDecimal value) {

		return value == null ? ZERO : value;
	}

	/**
	 * Round a decimal ratio to four places.
	 */
	private double roundRatio(double value) {

		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return 0.0;
		}

		return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
	}

	/**
	 * Scale monetary values to two decimal places.
	 */
	private BigDecimal scale2(BigDecimal value) {

		return safe(value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
	}

	/**
	 * Normalize GSTIN.
	 */
	private String normalizeGstin(String gstin) {

		return gstin == null ? "" : gstin.trim().toUpperCase(Locale.ROOT);
	}

	/**
	 * Validate GSTIN format.
	 */
	private void validateGstin(String gstin) {

		if (!hasText(gstin)) {

			throw new GstAnalyticsException("GSTIN input parameter cannot be empty or null.");
		}

		if (!GSTIN_PATTERN.matcher(gstin).matches()) {

			throw new GstAnalyticsException("Invalid GSTIN format. Expected a valid 15-character GSTIN.");
		}
	}

	/**
	 * Check non-empty text.
	 */
	private boolean hasText(String value) {

		return value != null && !value.trim().isEmpty();
	}

	// =========================================================
	// AGGREGATED METRICS
	// =========================================================

	/**
	 * Internal container for lifetime financial metrics.
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
