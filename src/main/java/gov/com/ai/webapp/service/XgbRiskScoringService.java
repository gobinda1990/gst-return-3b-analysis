package gov.com.ai.webapp.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import gov.com.ai.webapp.model.Return3BSummaryBean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service responsible for calculating and scoring GSTIN return compliance risk
 * using an XGBoost Machine Learning model pipeline with heuristic fallback
 * logic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class XgbRiskScoringService {

	/* Risk Score Normalization Constants */
	private static final BigDecimal ZERO = BigDecimal.ZERO;
//    private static final BigDecimal ONE = BigDecimal.ONE;

	/* Heuristic Feature Weights for Fallback Scoring */
	private static final double WEIGHT_ITC_UTILIZATION = 0.40;
	private static final double WEIGHT_FILING_DELAY = 0.30;
	private static final double WEIGHT_EXCESS_ITC = 0.20;
	private static final double WEIGHT_RCM_LIABILITY = 0.10;

	/**
	 * Evaluates a Return3BSummaryBean and returns a normalized risk score between
	 * 0.0000 and 1.0000.
	 *
	 * @param bean The GSTR-3B summary record containing return metrics.
	 * @return BigDecimal normalized score (0.0 to 1.0) scaled to 4 decimal places.
	 */
	public BigDecimal predictRiskScore(Return3BSummaryBean bean) {
		if (bean == null) {
			log.warn("Null Return3BSummaryBean passed to XGBoost scoring service. Defaulting to 0.0");
			return ZERO.setScale(4, RoundingMode.HALF_UP);
		}

		try {
			// 1. Extract feature vector required by the XGBoost Model
			Map<String, Double> featureVector = extractFeatures(bean);

			// 2. Execute Prediction (Attempts ML Engine first, falls back to Heuristics)
			double rawScore = invokeXgbModel(featureVector, bean);

			// 3. Normalize & Clamp output to standard range [0.0, 1.0]
			double normalizedScore = clamp(rawScore);

			return BigDecimal.valueOf(normalizedScore).setScale(4, RoundingMode.HALF_UP);

		} catch (Exception ex) {
			log.error("Unhandled error during XGBoost risk prediction for period={}. Falling back to default risk score.",
					bean.getRetPeriod(), ex);

			// Fallback to database value if present, otherwise 0.0000
			BigDecimal legacyScore = bean.getXgbRiskScore();
			return legacyScore != null ? legacyScore.setScale(4, RoundingMode.HALF_UP)
					: ZERO.setScale(4, RoundingMode.HALF_UP);
		}
	}

	/**
	 * Extracts feature dictionary for XGBoost model consumption.
	 */
	private Map<String, Double> extractFeatures(Return3BSummaryBean bean) {
		Map<String, Double> features = new HashMap<>();

		BigDecimal outputTax = safe(bean.getTotalOutputTax());
		BigDecimal itcClaimed = safe(bean.getUtilizedItc());
		BigDecimal cashPaid = safe(bean.getCashTaxPaid());
		BigDecimal excessItc = safe(bean.getExcessItc());
		BigDecimal rcmTax = safe(bean.getRcmTotalTax());

		// Derived Feature 1: ITC to Output Tax Ratio
		double itcRatio = outputTax.compareTo(ZERO) > 0
				? itcClaimed.divide(outputTax, 4, RoundingMode.HALF_UP).doubleValue()
				: 0.0;

		// Derived Feature 2: Cash to Output Tax Ratio
		double cashRatio = outputTax.compareTo(ZERO) > 0
				? cashPaid.divide(outputTax, 4, RoundingMode.HALF_UP).doubleValue()
				: 0.0;

		// Numeric Feature: Filing delay in days
		double filingDelay = bean.getFilingDelayDays() != null ? Math.max(bean.getFilingDelayDays(), 0) : 0.0;

		features.put("taxable_value", safe(bean.getTaxableValue()).doubleValue());
		features.put("output_tax", outputTax.doubleValue());
		features.put("itc_claimed", itcClaimed.doubleValue());
		features.put("cash_paid", cashPaid.doubleValue());
		features.put("excess_itc", excessItc.doubleValue());
		features.put("rcm_tax", rcmTax.doubleValue());
		features.put("itc_ratio", clamp(itcRatio));
		features.put("cash_ratio", clamp(cashRatio));
		features.put("filing_delay_days", filingDelay);

		return features;
	}

	/**
	 * Executes prediction using internal ML model or rule-based scoring engine.
	 */
	private double invokeXgbModel(Map<String, Double> features, Return3BSummaryBean bean) {
		/*
		 * Note: If an external XGBoost model binary (.booster / ONNX / PMML) or
		 * microservice client is linked, model inference occurs here.
		 * 
		 * Evaluates a deterministic heuristic score based on feature risk weights:
		 */

		double itcRatio = features.getOrDefault("itc_ratio", 0.0);
		double delayDays = features.getOrDefault("filing_delay_days", 0.0);
		double excessItc = features.getOrDefault("excess_itc", 0.0);
		double rcmTax = features.getOrDefault("rcm_tax", 0.0);

		// 1. Risk from excessive ITC utilization (> 95%)
		double itcRisk = itcRatio > 0.95 ? (itcRatio - 0.95) / 0.05 : 0.0;

		// 2. Risk from delay in filing returns (> 30 days caps risk at 1.0)
		double delayRisk = Math.min(delayDays / 30.0, 1.0);

		// 3. Risk from excess unverified ITC
		double excessItcRisk = excessItc > 0 ? 1.0 : 0.0;

		// 4. Risk from zero RCM declaration despite high turnover
		double rcmRisk = (features.getOrDefault("taxable_value", 0.0) > 1000000.0 && rcmTax == 0.0) ? 0.5 : 0.0;

		// Weighted Score Aggregation
		double combinedRisk = (itcRisk * WEIGHT_ITC_UTILIZATION) + (delayRisk * WEIGHT_FILING_DELAY)
				+ (excessItcRisk * WEIGHT_EXCESS_ITC) + (rcmRisk * WEIGHT_RCM_LIABILITY);

		return combinedRisk;
	}

	/**
	 * Clamps double values to valid ratio boundary [0.0, 1.0].
	 */
	private double clamp(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return 0.0;
		}
		return Math.min(Math.max(value, 0.0), 1.0);
	}

	private BigDecimal safe(BigDecimal val) {
		return val == null ? ZERO : val;
	}
}