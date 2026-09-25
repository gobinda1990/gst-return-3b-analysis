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
 * Service responsible for calculating and scoring GSTIN return compliance risk.
 *
 * FIX APPLIED: GstPredictionService builds a 26-feature vector (see its
 * FEATURE ORDER doc) and calls predict(float[]) on this service - but that
 * method did not exist before this fix, so the code did not compile, and the
 * 26-feature vector was never actually consumed anywhere.
 *
 * predict(float[]) below is a heuristic fallback, same spirit as the
 * original bean-based heuristic, but reading from the 26-feature vector by
 * index instead of re-deriving its own separate (and inconsistent) feature
 * set from the bean. It is NOT a real trained XGBoost model inference call -
 * there is no model binary / ONNX / PMML artifact wired in here. If a real
 * serialized model exists, replace the body of predict(float[]) with the
 * actual model.predict(features) call and keep this heuristic only as the
 * catch-block fallback.
 *
 * The original predictRiskScore(Return3BSummaryBean) is kept for any other
 * callers that still depend on it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class XgbRiskScoringService {

	/* Risk Score Normalization Constants */
	private static final BigDecimal ZERO = BigDecimal.ZERO;

	/* Heuristic Feature Weights for Fallback Scoring (bean-based path) */
	private static final double WEIGHT_ITC_UTILIZATION = 0.40;
	private static final double WEIGHT_FILING_DELAY = 0.30;
	private static final double WEIGHT_EXCESS_ITC = 0.20;
	private static final double WEIGHT_RCM_LIABILITY = 0.10;

	/*
	 * Heuristic Feature Weights for the 26-feature vector path.
	 * Indices below correspond to GstPredictionService's documented
	 * FEATURE ORDER (1-based feature N == array index N-1).
	 */
	private static final int IDX_ITC_UTILIZATION_RATIO = 5;   // feature 6
	private static final int IDX_ITC_TO_TAX_RATIO = 6;        // feature 7
	private static final int IDX_RCM_TO_TAX_RATIO = 8;        // feature 9
	private static final int IDX_FILING_DELAY_DAYS = 11;      // feature 12
	private static final int IDX_GROWTH_VOLATILITY = 24;      // feature 25
//	private static final int IDX_GROWTH_CONSISTENCY = 25;     // feature 26

	private static final double VEC_WEIGHT_ITC_UTILIZATION = 0.35;
	private static final double VEC_WEIGHT_FILING_DELAY = 0.30;
	private static final double VEC_WEIGHT_GROWTH_VOLATILITY = 0.20;
	private static final double VEC_WEIGHT_RCM_LIABILITY = 0.15;

	/**
	 * Entry point used by GstPredictionService: scores a pre-built 26-feature
	 * vector and returns a risk score in [0.0, 1.0].
	 *
	 * @param features 26-length feature vector, order per
	 *                 GstPredictionService#build26FeatureVector
	 * @return risk score clamped to [0.0, 1.0]
	 */
	public float predict(float[] features) {

		if (features == null || features.length == 0) {
			log.warn("Null/empty feature vector passed to XGBoost scoring service. Defaulting to 0.0");
			return 0.0f;
		}

		try {

			double itcUtilizationRatio = safeGet(features, IDX_ITC_UTILIZATION_RATIO);
			double itcToTaxRatio = safeGet(features, IDX_ITC_TO_TAX_RATIO);
			double rcmToTaxRatio = safeGet(features, IDX_RCM_TO_TAX_RATIO);
			double filingDelayDays = safeGet(features, IDX_FILING_DELAY_DAYS);
			double growthVolatility = safeGet(features, IDX_GROWTH_VOLATILITY);

			// 1. Risk from excessive ITC utilization (> 95%)
			double itcRisk = itcUtilizationRatio > 0.95 ? Math.min((itcUtilizationRatio - 0.95) / 0.05, 1.0) : 0.0;

			// 2. Risk from delay in filing returns (>= 30 days caps risk at 1.0)
			double delayRisk = Math.min(Math.max(filingDelayDays, 0.0) / 30.0, 1.0);

			// 3. Risk from claimed ITC exceeding eligible ITC-to-tax norms (proxy
			//    for the old "excess ITC" signal, using what's actually available
			//    in the 26-feature vector)
			double excessItcRisk = itcToTaxRatio > 1.0 ? 1.0 : 0.0;

			// 4. Risk from elevated growth volatility
			double volatilityRisk = Math.min(Math.max(growthVolatility, 0.0), 1.0);

			// 5. Risk from disproportionate RCM liability relative to output tax
			double rcmRisk = Math.min(Math.max(rcmToTaxRatio, 0.0), 1.0);

			double combinedRisk = (itcRisk * VEC_WEIGHT_ITC_UTILIZATION) + (delayRisk * VEC_WEIGHT_FILING_DELAY)
					+ (Math.max(excessItcRisk, volatilityRisk) * VEC_WEIGHT_GROWTH_VOLATILITY)
					+ (rcmRisk * VEC_WEIGHT_RCM_LIABILITY);

			return (float) clamp(combinedRisk);

		} catch (Exception ex) {

			log.error("Unhandled error during XGBoost risk prediction (vector path). Falling back to 0.0.", ex);

			return 0.0f;
		}
	}

	private double safeGet(float[] features, int index) {
		return index >= 0 && index < features.length ? features[index] : 0.0;
	}

	/**
	 * Evaluates a Return3BSummaryBean and returns a normalized risk score between
	 * 0.0000 and 1.0000. Retained for callers that score a single bean directly
	 * rather than a pre-built 26-feature vector.
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