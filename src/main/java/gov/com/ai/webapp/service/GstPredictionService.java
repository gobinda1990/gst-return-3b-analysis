package gov.com.ai.webapp.service;

import gov.com.ai.webapp.model.GstMlDto.*;
import gov.com.ai.webapp.model.Return3BSummaryBean;
import gov.com.ai.webapp.repository.Return3bRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ================================================================ GSTR-3B AI
 * PREDICTION SERVICE
 * ================================================================
 *
 * Model: GSTR3B-XGB-26-V1
 *
 * Feature count: 26
 *
 * Selected threshold: 0.51
 *
 * FIXES APPLIED (see review notes):
 * 1. History is now sorted by a parsed YearMonth instead of the raw
 *    "MMyyyy" string. String comparison of MMyyyy does NOT match
 *    chronological order across year boundaries (e.g. "012027" < "122026"
 *    lexicographically, even though Jan-2027 is after Dec-2026). This was
 *    silently corrupting "latest record" selection and every downstream
 *    growth/delay calculation whenever history spanned a year boundary.
 * 2. Growth features (1M/3M/6M/12M) are now looked up by the actual target
 *    YearMonth instead of a raw array-index offset, so a gap in filing
 *    history (a missed return period) no longer silently shifts every
 *    growth figure to the wrong period.
 * 3. Predicted ITC is now capped against max HISTORICAL ITC, not max
 *    historical output tax (those are different quantities).
 * 4. Calls xgbRiskScoringService.predict(float[]) which now actually
 *    exists and consumes the 26-feature vector (previously the vector was
 *    built but never used by the scoring service).
 *
 * ================================================================
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GstPredictionService {

	private final Return3bRepository return3bRepository;

	/**
	 * XGBoost model loader/inference service.
	 */
	private final XgbRiskScoringService xgbRiskScoringService;

	// ================================================================
	// MODEL CONFIGURATION
	// ================================================================

	private static final String MODEL_VERSION = "GSTR3B-XGB-26-V1";

	private static final String PREDICTION_SOURCE = "XGBOOST_26_FEATURE";

	private static final int FEATURE_COUNT = 26;

	/**
	 * Selected validation threshold.
	 */
	private static final float PREDICTION_THRESHOLD = 0.51f;

	// ================================================================
	// HISTORICAL CONFIGURATION
	// ================================================================

	private static final int HISTORICAL_MONTHS_LOOKBACK = 12;

	private static final double RUPEES_TO_LAKHS = 100_000.0;

	private static final double LATE_FEE_PER_DAY_STANDARD = 50.0;

	/**
	 * Safeguard:
	 *
	 * Predicted tax cannot exceed 3x the maximum historical monthly value.
	 */
	private static final double MAX_HISTORICAL_MULTIPLIER_CAP = 3.0;

	// ================================================================
	// DATE FORMAT
	// ================================================================

	private static final DateTimeFormatter RETURN_PERIOD_FORMAT = DateTimeFormatter.ofPattern("MMyyyy");

	// ================================================================
	// MAIN PREDICTION METHOD
	// ================================================================

	/**
	 * Predicts the next GSTR-3B return period for a GSTIN.
	 *
	 * @param request prediction request
	 * @return PredictionResponse
	 */
	public PredictionResponse predictNextMonth(PredictionRequest request) {

		validateRequest(request);

		String cleanGstin = request.getGstin().trim().toUpperCase();

		log.info("[GSTR3B AI] Starting prediction for GSTIN [{}]", cleanGstin);

		// ------------------------------------------------------------
		// LOAD HISTORY
		// ------------------------------------------------------------

		List<Return3BSummaryBean> historyBeans = return3bRepository.findHistory(cleanGstin, HISTORICAL_MONTHS_LOOKBACK);

		if (historyBeans == null) {
			historyBeans = new ArrayList<>();
		}

		/*
		 * IMPORTANT:
		 *
		 * Do NOT call Return3BSummaryBean::normalize here.
		 *
		 * The current Return3BSummaryBean does not define a compatible normalize()
		 * method.
		 *
		 * Work directly with repository DTO values.
		 *
		 * FIX: sort by parsed YearMonth, not the raw "MMyyyy" string - string
		 * comparison does not match chronological order across year boundaries.
		 * Records with an unparseable retPeriod are dropped rather than silently
		 * mis-sorted.
		 */
		historyBeans = historyBeans.stream()
				.filter(bean -> bean != null)
				.filter(bean -> bean.getRetPeriod() != null)
				.filter(bean -> parseReturnPeriod(bean.getRetPeriod()) != null)
				.sorted(Comparator.comparing(bean -> parseReturnPeriod(bean.getRetPeriod())))
				.toList();

		log.info("[GSTR3B AI] Historical records [{}]: {}", cleanGstin, historyBeans.size());

		// ------------------------------------------------------------
		// TARGET RETURN PERIOD
		// ------------------------------------------------------------

		YearMonth targetReturnPeriod = deriveNextReturnPeriod(historyBeans);

		String returnPeriodStr = targetReturnPeriod.format(RETURN_PERIOD_FORMAT);

		log.info("[GSTR3B AI] Prediction target period [{}]: {}", cleanGstin, returnPeriodStr);

		// ------------------------------------------------------------
		// NO HISTORY
		// ------------------------------------------------------------

		if (historyBeans.isEmpty()) {

			log.info("[GSTR3B AI] No historical data for GSTIN [{}]", cleanGstin);

			return buildNilPredictionResponse(cleanGstin, returnPeriodStr, targetReturnPeriod);
		}

		// ------------------------------------------------------------
		// NIL HISTORY
		// ------------------------------------------------------------

		boolean isPureNilHistory = isPureNilHistory(historyBeans);

		if (isPureNilHistory) {

			log.info("[GSTR3B AI] GSTIN [{}] has pure NIL history", cleanGstin);

			return buildNilPredictionResponse(cleanGstin, returnPeriodStr, targetReturnPeriod);
		}

		// ============================================================
		// BUILD 26 FEATURES
		// ============================================================

		float[] features = build26FeatureVector(historyBeans);

		validateFeatureVector(features);

		log.debug("[GSTR3B AI] 26-feature vector generated for [{}]", cleanGstin);

		// ============================================================
		// XGBOOST PREDICTION
		// ============================================================

		float riskScore = xgbRiskScoringService.predict(features);

		riskScore = sanitizeRiskScore(riskScore);

		log.info("[GSTR3B AI] GSTIN [{}] risk score = {}", cleanGstin, String.format("%.6f", riskScore));

		// ============================================================
		// BINARY PREDICTION
		// ============================================================

		boolean defaultPrediction = riskScore >= PREDICTION_THRESHOLD;

		// ============================================================
		// RISK CATEGORY
		// ============================================================

		String riskCategory = deriveRiskCategory(riskScore);

		// ============================================================
		// HISTORICAL TAX METRICS
		// ============================================================

		double avgOutputRs = calculateAvgOutputTaxRs(historyBeans);

		double maxHistoricalOutputRs = calculateMaxOutputTaxRs(historyBeans);

		double avgItcRs = calculateAvgUtilizedItcRs(historyBeans);

		double maxHistoricalItcRs = calculateMaxUtilizedItcRs(historyBeans);

		/*
		 * TEMPORARY DIAGNOSTIC LOGGING
		 *
		 * Added to track down a magnitude bug: predictedOutputTax for GSTIN
		 * 19APKPS6696B1Z9 came back as ~Rs 31.7 billion (Rs 3173 Cr) against a
		 * recent per-period output tax around Rs 2.2 lakh. Dumping every
		 * historical record's totalOutputTax plus the derived avg/max lets us
		 * see whether one record is corrupted/outlier-inflated (which would
		 * also inflate the sanitizePredictionRs cap, since the cap is
		 * max*3 - a corrupted max defeats its own safeguard). Remove once the
		 * root cause is confirmed.
		 */
		if (log.isWarnEnabled()) {

			for (Return3BSummaryBean bean : historyBeans) {

				log.warn("[GSTR3B AI][DIAG] GSTIN={} period={} totalOutputTax={} taxableValue={} utilizedItc={}",
						cleanGstin, bean.getRetPeriod(), bean.getTotalOutputTax(), bean.getTaxableValue(),
						bean.getUtilizedItc());
			}

			log.warn("[GSTR3B AI][DIAG] GSTIN={} avgOutputRs={} maxHistoricalOutputRs={} avgItcRs={} maxHistoricalItcRs={}",
					cleanGstin, avgOutputRs, maxHistoricalOutputRs, avgItcRs, maxHistoricalItcRs);
		}

		// ============================================================
		// FORECAST TAX VALUES
		// ============================================================

		double predictedOutputTaxRs = calculatePredictedOutputTax(historyBeans);

		double predictedItcRs = calculatePredictedItc(historyBeans);

		if (log.isWarnEnabled()) {
			log.warn("[GSTR3B AI][DIAG] GSTIN={} predictedOutputTaxRs(pre-cap)={} predictedItcRs(pre-cap)={}",
					cleanGstin, predictedOutputTaxRs, predictedItcRs);
		}

		predictedOutputTaxRs = sanitizePredictionRs(predictedOutputTaxRs, avgOutputRs, maxHistoricalOutputRs);

		// FIX: cap ITC prediction against max historical ITC, not max
		// historical output tax - they are different quantities.
		predictedItcRs = sanitizePredictionRs(predictedItcRs, avgItcRs, maxHistoricalItcRs);

		if (log.isWarnEnabled()) {
			log.warn("[GSTR3B AI][DIAG] GSTIN={} predictedOutputTaxRs(post-cap)={} predictedItcRs(post-cap)={}",
					cleanGstin, predictedOutputTaxRs, predictedItcRs);
		}

		// ------------------------------------------------------------
		// ITC CANNOT EXCEED OUTPUT TAX
		// ------------------------------------------------------------

		predictedItcRs = Math.min(predictedItcRs, predictedOutputTaxRs);

		// ------------------------------------------------------------
		// CASH TAX
		// ------------------------------------------------------------

		double forecastedCashRs = Math.max(0.0, predictedOutputTaxRs - predictedItcRs);

		// ============================================================
		// DELAY
		// ============================================================

		int delayDays = calculatePredictedDelay(historyBeans);

		// ============================================================
		// EFFECTIVE TAX RATE
		// ============================================================

		double effectiveTaxRate = deriveEffectiveTaxRate(historyBeans);

		// ============================================================
		// PREDICTED TAXABLE VALUE
		// ============================================================

		double estimatedTaxableValue;

		if (effectiveTaxRate > 0.0) {

			estimatedTaxableValue = predictedOutputTaxRs / effectiveTaxRate;

		} else {

			estimatedTaxableValue = predictedOutputTaxRs;
		}

		estimatedTaxableValue = round2(estimatedTaxableValue);

		// ============================================================
		// ITC RATIO
		// ============================================================

		double itcRatio = predictedOutputTaxRs > 0.0 ? predictedItcRs / predictedOutputTaxRs : 0.0;

		itcRatio = Math.min(1.0, Math.max(0.0, itcRatio));

		// ============================================================
		// STATUTORY DUE DATE
		// ============================================================

		LocalDate statutoryDueDate = targetReturnPeriod.plusMonths(1).atDay(20);

		// ============================================================
		// ESTIMATED FILING DATE
		// ============================================================

		LocalDate estimatedFilingDate = statutoryDueDate.plusDays(delayDays);

		// ============================================================
		// LATE FEE
		// ============================================================

		double calculatedLateFee = delayDays * LATE_FEE_PER_DAY_STANDARD;

		// ============================================================
		// PRIMARY RISK FACTOR
		// ============================================================

		String primaryRiskFactor = derivePrimaryRiskFactor(features, riskScore, delayDays);

		// ============================================================
		// CASH LIABILITY FORECAST
		// ============================================================

		CashLiabilityForecast forecast = CashLiabilityForecast.builder().returnPeriod(returnPeriodStr)
				.predictedTaxValue(round2(predictedOutputTaxRs)).predictedItcValue(round2(predictedItcRs))
				.predictedCashValue(round2(forecastedCashRs)).build();

		// ============================================================
		// RESPONSE
		// ============================================================

		PredictionResponse response = PredictionResponse.builder()

				// GST
				.gstin(cleanGstin).predictionPeriod(returnPeriodStr).targetRetPeriod(returnPeriodStr)

				// AI
				.riskCategory(riskCategory).riskTrend(riskCategory)

				.lateFilingRiskScore((double) riskScore)

				/*
				 * React compatibility field.
				 *
				 * This is the XGBoost score. It must not be interpreted as a calibrated
				 * probability unless calibration is separately performed.
				 */
				.probabilityOfDefault((double) riskScore)

				.defaultPrediction(defaultPrediction)

				.defaultPredicted(defaultPrediction)

				.predictionSource(PREDICTION_SOURCE)

				.modelVersion(MODEL_VERSION)

				.primaryRiskFactor(primaryRiskFactor)

				// Filing
				.dueDate(statutoryDueDate.toString())

				.estimatedFilingDate(estimatedFilingDate.toString())

				.delayDays(delayDays)

				.calculatedLateFee(round2(calculatedLateFee))

				.calculatedDt(LocalDate.now().toString())

				// Tax summary
				.predictedTaxableVal(estimatedTaxableValue)

				.predictedOutputTax(round2(predictedOutputTaxRs))

				.predictedItcAvail(round2(predictedItcRs))

				.predictedItcRatio(round4(itcRatio))

				.forecastedCashLiability(List.of(forecast))

				.build();

		log.info(
				"[GSTR3B AI] Prediction completed | " + "GSTIN={} | Period={} | Score={} | "
						+ "Threshold={} | Prediction={} | Category={}",
				cleanGstin, returnPeriodStr, String.format("%.6f", riskScore), PREDICTION_THRESHOLD, defaultPrediction,
				riskCategory);

		return response;
	}

	// ================================================================
	// 26 FEATURE VECTOR
	// ================================================================

	/**
	 * Builds the 26-feature vector.
	 *
	 * FIX: this now delegates entirely to {@link Gstr3bFeatureBuilder}, the
	 * same feature-construction code used at training time, instead of
	 * maintaining a second, hand-written copy of the feature math here.
	 *
	 * The earlier version of this method recomputed the ratios and growth
	 * features itself, and several of them diverged from the canonical
	 * training-time formulas (e.g. ratios divided by outputTax here vs.
	 * outputTax+rcmTax "liability" in the real builder; features 10/11 used
	 * different numerator/denominator pairs entirely). That is a train/serve
	 * skew bug: the model would have been fed inference-time features that
	 * don't match what it was trained on, which silently produces wrong risk
	 * scores without ever throwing an error. Delegating to the shared builder
	 * removes that whole class of bug by construction - there is now exactly
	 * one place that defines what the 26 features are.
	 *
	 * NOTE: history passed in MUST already be sorted chronologically ascending
	 * (see predictNextMonth's fixed sort above).
	 */
	private float[] build26FeatureVector(List<Return3BSummaryBean> history) {

		if (history == null || history.isEmpty()) {
			return new float[FEATURE_COUNT];
		}

		Return3BSummaryBean latest = history.get(history.size() - 1);

		// ------------------------------------------------------------
		// PERIOD-KEYED LOOKUP so previous1/3/6/12 are resolved by actual
		// calendar month, not by a raw "N records back" array offset - a
		// gap/missing filing no longer silently shifts lookups to the
		// wrong period.
		// ------------------------------------------------------------

		Map<YearMonth, Return3BSummaryBean> historyByPeriod = new HashMap<>();

		for (Return3BSummaryBean bean : history) {

			YearMonth period = parseReturnPeriod(bean.getRetPeriod());

			if (period != null) {
				historyByPeriod.put(period, bean);
			}
		}

		YearMonth latestPeriod = parseReturnPeriod(latest.getRetPeriod());

		Return3BSummaryBean previous1 = lookupPeriod(historyByPeriod, latestPeriod, 1);

		Return3BSummaryBean previous3 = lookupPeriod(historyByPeriod, latestPeriod, 3);

		Return3BSummaryBean previous6 = lookupPeriod(historyByPeriod, latestPeriod, 6);

		Return3BSummaryBean previous12 = lookupPeriod(historyByPeriod, latestPeriod, 12);

		// ------------------------------------------------------------
		// GROWTH VOLATILITY / CONSISTENCY (record-over-record; deliberately
		// measures filing-to-filing swings rather than fixed calendar-month
		// deltas, so it stays index-based over the sorted history)
		// ------------------------------------------------------------

		float growthVolatility = (float) calculateGrowthVolatility(history);

		float growthConsistency = (float) calculateGrowthConsistency(history);

		return Gstr3bFeatureBuilder.build(latest, previous1, previous3, previous6, previous12, growthVolatility,
				growthConsistency);
	}

	private Return3BSummaryBean lookupPeriod(Map<YearMonth, Return3BSummaryBean> historyByPeriod,
			YearMonth latestPeriod, int monthsBack) {

		return latestPeriod == null ? null : historyByPeriod.get(latestPeriod.minusMonths(monthsBack));
	}

	// ================================================================
	// GROWTH VOLATILITY
	// ================================================================

	private double calculateGrowthVolatility(List<Return3BSummaryBean> history) {

		if (history == null || history.size() < 3) {

			return 0.0;
		}

		List<Double> growths = new ArrayList<>();

		for (int i = 1; i < history.size(); i++) {

			double previous = value(history.get(i - 1).getTaxableValue());

			double current = value(history.get(i).getTaxableValue());

			if (Math.abs(previous) < 0.000001) {
				continue;
			}

			double growth = (current - previous) / Math.abs(previous);

			growths.add(clampGrowth(growth));
		}

		if (growths.size() < 2) {
			return 0.0;
		}

		double mean = growths.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

		double variance = growths.stream().mapToDouble(g -> Math.pow(g - mean, 2)).average().orElse(0.0);

		return clampFeature(Math.sqrt(variance));
	}

	// ================================================================
	// GROWTH CONSISTENCY
	// ================================================================

	private double calculateGrowthConsistency(List<Return3BSummaryBean> history) {

		if (history == null || history.size() < 3) {

			return 0.0;
		}

		int positive = 0;
		int negative = 0;
		int total = 0;

		for (int i = 1; i < history.size(); i++) {

			double previous = value(history.get(i - 1).getTaxableValue());

			double current = value(history.get(i).getTaxableValue());

			if (Math.abs(previous) < 0.000001) {
				continue;
			}

			double growth = (current - previous) / Math.abs(previous);

			if (growth > 0.0) {

				positive++;

			} else if (growth < 0.0) {

				negative++;
			}

			total++;
		}

		if (total == 0) {
			return 0.0;
		}

		return Math.max(positive, negative) / (double) total;
	}

	// ================================================================
	// PREDICTED OUTPUT TAX
	// ================================================================

	private double calculatePredictedOutputTax(List<Return3BSummaryBean> history) {

		if (history == null || history.isEmpty()) {

			return 0.0;
		}

		int sampleSize = Math.min(3, history.size());

		double sum = 0.0;

		for (int i = history.size() - sampleSize; i < history.size(); i++) {

			sum += value(history.get(i).getTotalOutputTax());
		}

		return sum / sampleSize;
	}

	// ================================================================
	// PREDICTED ITC
	// ================================================================

	private double calculatePredictedItc(List<Return3BSummaryBean> history) {

		if (history == null || history.isEmpty()) {

			return 0.0;
		}

		int sampleSize = Math.min(3, history.size());

		double sum = 0.0;

		for (int i = history.size() - sampleSize; i < history.size(); i++) {

			sum += value(history.get(i).getUtilizedItc());
		}

		return sum / sampleSize;
	}

	// ================================================================
	// SANITIZE PREDICTION
	// ================================================================

	private double sanitizePredictionRs(double predictedRs, double avgRs, double maxHistoricalRs) {

		if (Double.isNaN(predictedRs) || Double.isInfinite(predictedRs) || predictedRs < 0.0) {

			return Math.max(0.0, avgRs);
		}

		double upperCapRs = Math.max(maxHistoricalRs * MAX_HISTORICAL_MULTIPLIER_CAP, 50_000.0);

		if (predictedRs > upperCapRs) {

			log.warn("[AI Safeguard] Prediction capped | " + "Original={} | Cap={}", predictedRs, upperCapRs);

			return round2(upperCapRs);
		}

		return round2(predictedRs);
	}

	// ================================================================
	// HISTORICAL METRICS
	// ================================================================

	private double calculateAvgOutputTaxRs(List<Return3BSummaryBean> history) {

		if (history == null || history.isEmpty()) {

			return 0.0;
		}

		return history.stream().mapToDouble(b -> value(b.getTotalOutputTax())).average().orElse(0.0);
	}

	private double calculateMaxOutputTaxRs(List<Return3BSummaryBean> history) {

		if (history == null || history.isEmpty()) {

			return 0.0;
		}

		return history.stream().mapToDouble(b -> value(b.getTotalOutputTax())).max().orElse(0.0);
	}

	private double calculateAvgUtilizedItcRs(List<Return3BSummaryBean> history) {

		if (history == null || history.isEmpty()) {

			return 0.0;
		}

		return history.stream().mapToDouble(b -> value(b.getUtilizedItc())).average().orElse(0.0);
	}

	// FIX: new method - max historical ITC, used to cap the ITC prediction
	// (previously the code incorrectly reused calculateMaxOutputTaxRs for this).
	private double calculateMaxUtilizedItcRs(List<Return3BSummaryBean> history) {

		if (history == null || history.isEmpty()) {

			return 0.0;
		}

		return history.stream().mapToDouble(b -> value(b.getUtilizedItc())).max().orElse(0.0);
	}

	// ================================================================
	// DELAY
	// ================================================================

	private int calculatePredictedDelay(List<Return3BSummaryBean> history) {

		if (history == null || history.isEmpty()) {

			return 0;
		}

		int sampleSize = Math.min(3, history.size());

		double weightedSum = 0.0;
		double totalWeight = 0.0;

		double weight = 1.0;

		for (int i = history.size() - sampleSize; i < history.size(); i++) {

			Integer delayValue = history.get(i).getFilingDelayDays();

			double delay = delayValue != null ? delayValue.doubleValue() : 0.0;

			weightedSum += delay * weight;

			totalWeight += weight;

			weight++;
		}

		if (totalWeight == 0.0) {
			return 0;
		}

		return Math.max(0, (int) Math.round(weightedSum / totalWeight));
	}

	// ================================================================
	// NEXT RETURN PERIOD
	// ================================================================

	private YearMonth deriveNextReturnPeriod(List<Return3BSummaryBean> history) {

		if (history != null && !history.isEmpty()) {

			Return3BSummaryBean latest = history.get(history.size() - 1);

			YearMonth latestPeriod = parseReturnPeriod(latest.getRetPeriod());

			if (latestPeriod != null) {
				return latestPeriod.plusMonths(1);
			}
		}

		return YearMonth.now().plusMonths(1);
	}

	// ================================================================
	// RETURN PERIOD PARSING (MMyyyy -> YearMonth)
	// ================================================================

	private static YearMonth parseReturnPeriod(String retPeriod) {

		if (retPeriod == null || retPeriod.length() != 6) {
			return null;
		}

		try {

			int month = Integer.parseInt(retPeriod.substring(0, 2));

			int year = Integer.parseInt(retPeriod.substring(2, 6));

			if (month < 1 || month > 12) {
				return null;
			}

			return YearMonth.of(year, month);

		} catch (RuntimeException e) {

			log.warn("[GSTR3B AI] Invalid return period [{}]", retPeriod);

			return null;
		}
	}

	// ================================================================
	// EFFECTIVE TAX RATE
	// ================================================================

	private double deriveEffectiveTaxRate(List<Return3BSummaryBean> history) {

		if (history == null || history.isEmpty()) {

			return 0.18;
		}

		double totalTaxable = 0.0;
		double totalOutput = 0.0;

		for (Return3BSummaryBean bean : history) {

			totalTaxable += value(bean.getTaxableValue());

			totalOutput += value(bean.getTotalOutputTax());
		}

		if (totalTaxable > 0.0) {

			double rate = totalOutput / totalTaxable;

			if (rate > 0.01 && rate < 0.40) {

				return rate;
			}
		}

		return 0.18;
	}

	// ================================================================
	// RISK CATEGORY
	// ================================================================

	private String deriveRiskCategory(float riskScore) {

		if (riskScore >= 0.70f) {
			return "HIGH";
		}

		if (riskScore >= 0.35f) {
			return "MEDIUM";
		}

		return "LOW";
	}

	// ================================================================
	// PRIMARY RISK FACTOR
	// ================================================================

	private String derivePrimaryRiskFactor(float[] features, float riskScore, int delayDays) {

		if (delayDays > 15) {
			return "CHRONIC_FILING_DELAY";
		}

		/*
		 * Feature 7: ITC-to-tax ratio
		 */
		if (features.length >= 7 && features[6] > 0.95f) {

			return "HIGH_ITC_TO_TAX_RATIO";
		}

		/*
		 * Feature 13: 1-month taxable value growth
		 */
		if (features.length >= 13 && Math.abs(features[12]) > 0.50f) {

			return "HIGH_TURNOVER_CHANGE";
		}

		/*
		 * Feature 25: Growth volatility
		 */
		if (features.length >= 25 && features[24] > 0.50f) {

			return "HIGH_GROWTH_VOLATILITY";
		}

		/*
		 * Feature 26: Growth consistency
		 */
		if (features.length >= 26 && features[25] < 0.30f) {

			return "LOW_GROWTH_CONSISTENCY";
		}

		if (riskScore >= PREDICTION_THRESHOLD) {
			return "MODEL_RISK_SIGNAL";
		}

		return "NONE_DETECTED";
	}

	// ================================================================
	// NIL RESPONSE
	// ================================================================

	private PredictionResponse buildNilPredictionResponse(String gstin, String returnPeriod,
			YearMonth targetReturnPeriod) {

		LocalDate dueDate = targetReturnPeriod.plusMonths(1).atDay(20);

		CashLiabilityForecast forecast = CashLiabilityForecast.builder().returnPeriod(returnPeriod)
				.predictedTaxValue(0.0).predictedItcValue(0.0).predictedCashValue(0.0).build();

		return PredictionResponse.builder()

				.gstin(gstin)

				.predictionPeriod(returnPeriod)

				.targetRetPeriod(returnPeriod)

				.riskCategory("LOW")

				.riskTrend("LOW")

				.lateFilingRiskScore(0.0)

				.probabilityOfDefault(0.0)

				.defaultPrediction(false)

				.defaultPredicted(false)

				.predictionSource(PREDICTION_SOURCE)

				.modelVersion(MODEL_VERSION)

				.primaryRiskFactor("NIL_RETURN_HISTORY")

				.dueDate(dueDate.toString())

				.estimatedFilingDate(dueDate.toString())

				.delayDays(0)

				.calculatedLateFee(0.0)

				.calculatedDt(LocalDate.now().toString())

				.predictedTaxableVal(0.0)

				.predictedOutputTax(0.0)

				.predictedItcAvail(0.0)

				.predictedItcRatio(0.0)

				.forecastedCashLiability(List.of(forecast))

				.build();
	}

	// ================================================================
	// VALIDATION
	// ================================================================

	private void validateRequest(PredictionRequest request) {

		if (request == null) {

			throw new IllegalArgumentException("Prediction request cannot be null");
		}

		if (request.getGstin() == null || request.getGstin().trim().isEmpty()) {

			throw new IllegalArgumentException("GSTIN cannot be null or empty");
		}

		String gstin = request.getGstin().trim().toUpperCase();

		/*
		 * GSTIN format:
		 *
		 * 2 digit state code 5 uppercase letters 4 digits 1 uppercase letter 1
		 * alphanumeric Z 1 alphanumeric
		 */
		if (!gstin.matches("^[0-9]{2}[A-Z]{5}[0-9]{4}" + "[A-Z]{1}[1-9A-Z]{1}Z" + "[0-9A-Z]{1}$")) {

			throw new IllegalArgumentException("Invalid GSTIN format: " + gstin);
		}
	}

	private void validateFeatureVector(float[] features) {

		if (features == null) {

			throw new IllegalArgumentException("Feature vector cannot be null");
		}

		if (features.length != FEATURE_COUNT) {

			throw new IllegalArgumentException(
					"Invalid feature count. Expected " + FEATURE_COUNT + ", Actual " + features.length);
		}

		for (int i = 0; i < features.length; i++) {

			/*
			 * NaN is a deliberate, valid value here - not an error.
			 *
			 * Gstr3bFeatureBuilder.ratio() returns Float.NaN whenever a ratio's
			 * denominator is zero/undefined (e.g. itcToTaxRatio when both
			 * outputTax and rcmTax are zero for the current period). XGBoost
			 * natively treats NaN as "missing" and routes it during tree
			 * splits, so this must reach the model as-is rather than being
			 * rejected. Only reject a genuine Infinity, which should never
			 * legitimately occur from this feature builder.
			 */
			if (Float.isInfinite(features[i])) {

				throw new IllegalArgumentException("Invalid feature at index " + i + ": " + features[i]);
			}
		}
	}

	// ================================================================
	// UTILITY METHODS
	// ================================================================

	private double value(BigDecimal value) {

		return value == null ? 0.0 : value.doubleValue();
	}

	private double toLakhs(double rupees) {

		return rupees / RUPEES_TO_LAKHS;
	}

	private double clampFeature(double value) {

		if (Double.isNaN(value) || Double.isInfinite(value)) {

			return 0.0;
		}

		return Math.max(-10.0, Math.min(10.0, value));
	}

	private double clampGrowth(double value) {

		if (Double.isNaN(value) || Double.isInfinite(value)) {

			return 0.0;
		}

		return Math.max(-5.0, Math.min(5.0, value));
	}

	private float safeFloat(double value) {

		if (Double.isNaN(value) || Double.isInfinite(value)) {

			return 0.0f;
		}

		if (value > Float.MAX_VALUE) {
			return Float.MAX_VALUE;
		}

		if (value < -Float.MAX_VALUE) {
			return -Float.MAX_VALUE;
		}

		return (float) value;
	}

	private float sanitizeRiskScore(float score) {

		if (Float.isNaN(score) || Float.isInfinite(score)) {

			log.warn("[GSTR3B AI] Invalid XGBoost score received: {}", score);

			return 0.0f;
		}

		return Math.max(0.0f, Math.min(1.0f, score));
	}

	private double round2(double value) {

		return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
	}

	private double round4(double value) {

		return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
	}

	private boolean isPureNilHistory(List<Return3BSummaryBean> history) {

		if (history == null || history.isEmpty()) {

			return true;
		}

		return history.stream().allMatch(bean -> {

			double taxable = value(bean.getTaxableValue());

			double outputTax = value(bean.getTotalOutputTax());

			return Math.abs(taxable) < 0.000001 && Math.abs(outputTax) < 0.000001;
		});
	}

}