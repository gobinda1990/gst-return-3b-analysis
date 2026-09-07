package gov.com.ai.webapp.feature;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import gov.com.ai.webapp.model.Return3BSummaryBean;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Enterprise Production Feature Extractor for GSTR-3B ML Pipelines. Converts
 * historical database records into normalized, chronological feature vectors
 * compatible with native XGBoost / LightGBM DMatrix structures.
 */
@Slf4j
@Component
public class Gstr3BFeatureExtractor {

	public static final int REQUIRED_HISTORICAL_MONTHS = 6;
//	private static final DateTimeFormatter PERIOD_FORMATTER = DateTimeFormatter.ofPattern("MMyyyy");
	private static final double EPSILON = 1e-6;

	/**
	 * Extracts an 18-dimensional feature vector from GSTR-3B time-series data.
	 */
	public float[] extractFeatureVector(List<Return3BSummaryBean> sortedHistory) {
		if (sortedHistory == null || sortedHistory.isEmpty()) {
			throw new IllegalArgumentException(
					"Historical GSTR-3B records cannot be null or empty for feature extraction.");
		}

		int size = sortedHistory.size();
		Return3BSummaryBean latest = sortedHistory.get(size - 1);

		// 2. Rolling Window Averages (3-Month & 6-Month)
		double avgTaxable3m = calculateMovingAverage(sortedHistory, 3, b -> safeDouble(b.getTaxableValue()));
		double avgTaxable6m = calculateMovingAverage(sortedHistory, 6, b -> safeDouble(b.getTaxableValue()));

		double avgOutputTax3m = calculateMovingAverage(sortedHistory, 3, b -> safeDouble(b.getTotalOutputTax()));
		double avgOutputTax6m = calculateMovingAverage(sortedHistory, 6, b -> safeDouble(b.getTotalOutputTax()));

		double avgItc3m = calculateMovingAverage(sortedHistory, 3, b -> safeDouble(b.getEligibleItc()));
		double avgItc6m = calculateMovingAverage(sortedHistory, 6, b -> safeDouble(b.getEligibleItc()));

		// 3. Financial Growth Momentum Ratios (3M vs 6M Baseline)
		double taxableMomentum = (avgTaxable6m > EPSILON) ? (avgTaxable3m / avgTaxable6m) : 1.0;
		double taxMomentum = (avgOutputTax6m > EPSILON) ? (avgOutputTax3m / avgOutputTax6m) : 1.0;
		double itcMomentum = (avgItc6m > EPSILON) ? (avgItc3m / avgItc6m) : 1.0;

		// 4. On-the-fly Financial Ratio Computations
		double latestOutputTax = safeDouble(latest.getTotalOutputTax());
		double latestUtilizedItc = safeDouble(latest.getUtilizedItc());
		double latestCashPaid = safeDouble(latest.getCashTaxPaid());

		double computedItcToTaxRatio = (latestOutputTax > EPSILON) ? (latestUtilizedItc / latestOutputTax) : 0.0;
		double totalSettlement = latestUtilizedItc + latestCashPaid;
		double computedCashRatio = (totalSettlement > EPSILON) ? (latestCashPaid / totalSettlement) : 0.0;

		// 5. 6-Month Bounded Compliance Metrics
		int window6mStart = Math.max(0, size - 6);
		List<Return3BSummaryBean> history6m = sortedHistory.subList(window6mStart, size);

		double avgFilingDelay6m = history6m.stream()
				.mapToInt(b -> b.getFilingDelayDays() != null ? b.getFilingDelayDays() : 0).average().orElse(0.0);

		long totalLateFilings6m = history6m.stream()
				.filter(b -> b.getFilingDelayDays() != null && b.getFilingDelayDays() > 0).count();

		// 6. Volatility Analysis (12-Month Standard Deviation)
		double stdDevTaxable12m = calculateStandardDeviation(sortedHistory, 12, b -> safeDouble(b.getTaxableValue()));

		return new float[] { (float) safeDouble(latest.getTaxableValue()), (float) avgTaxable3m, (float) avgTaxable6m,
				(float) latestOutputTax, (float) avgOutputTax3m, (float) avgOutputTax6m,
				(float) safeDouble(latest.getEligibleItc()), (float) avgItc3m, (float) avgItc6m,
				(float) taxableMomentum, (float) taxMomentum, (float) itcMomentum, (float) computedItcToTaxRatio,
				(float) computedCashRatio, (float) avgFilingDelay6m, (float) totalLateFilings6m,
				(float) stdDevTaxable12m, (float) safeDouble(latest.getXgbRiskScore()) };
	}

	private double calculateMovingAverage(List<Return3BSummaryBean> history, int windowMonths,
			ToDoubleFunction<Return3BSummaryBean> mapper) {
		int size = history.size();
		int start = Math.max(0, size - windowMonths);
		return history.subList(start, size).stream().mapToDouble(mapper).average().orElse(0.0);
	}

	private double calculateStandardDeviation(List<Return3BSummaryBean> history, int windowMonths,
			ToDoubleFunction<Return3BSummaryBean> mapper) {
		int size = history.size();
		int start = Math.max(0, size - windowMonths);
		List<Return3BSummaryBean> window = history.subList(start, size);

		double mean = window.stream().mapToDouble(mapper).average().orElse(0.0);
		if (window.size() <= 1) {
			return 0.0;
		}

		double sumSquaredDiffs = window.stream().mapToDouble(b -> Math.pow(mapper.applyAsDouble(b) - mean, 2)).sum();

		return Math.sqrt(sumSquaredDiffs / (window.size() - 1));
	}

	private double safeDouble(BigDecimal val) {
		return val == null ? 0.0 : val.doubleValue();
	}
}