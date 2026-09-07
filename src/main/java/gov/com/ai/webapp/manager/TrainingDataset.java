package gov.com.ai.webapp.manager;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ml.dmlc.xgboost4j.java.DMatrix;
import ml.dmlc.xgboost4j.java.XGBoostError;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.ToDoubleFunction;

import gov.com.ai.webapp.model.Return3BSummaryBean;

/**
 * Encapsulates tabular GSTR-3B dataset observations for model training and
 * evaluation. Handles train/test dataset splitting and conversion into native
 * XGBoost C++ DMatrix memory spaces.
 */
@Slf4j
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingDataset implements Serializable {

	private static final long serialVersionUID = 1L;

	@Builder.Default
	private List<Return3BSummaryBean> records = new ArrayList<>();

	/**
	 * Functional interface for extracting target training labels ($y$) from domain records.
	 */
	@FunctionalInterface
	public interface TargetExtractor extends ToDoubleFunction<Return3BSummaryBean>, Serializable {
	}

	/**
	 * Result container for train/test splits.
	 */
	@Data
	@AllArgsConstructor
	public static class SplitResult {
		private final TrainingDataset trainDataset;
		private final TrainingDataset testDataset;
	}

	public void addRecord(Return3BSummaryBean record) {
		if (record != null) {
			Return3BSummaryBean.normalize(record);
			this.records.add(record);
		}
	}

	public int size() {
		return records != null ? records.size() : 0;
	}

	public boolean isEmpty() {
		return records == null || records.isEmpty();
	}

	/**
	 * Splits dataset into training and testing subsets using a deterministic seed.
	 * Includes defensive guards for small datasets (<= 1 record).
	 */
	public SplitResult trainTestSplit(double trainRatio, long randomSeed) {
		if (isEmpty()) {
			return new SplitResult(new TrainingDataset(), new TrainingDataset());
		}

		if (records.size() == 1) {
			log.warn("[TrainingDataset] Dataset contains only 1 record. Assigning to training set.");
			return new SplitResult(
					TrainingDataset.builder().records(new ArrayList<>(records)).build(),
					new TrainingDataset()
			);
		}

		List<Return3BSummaryBean> shuffled = new ArrayList<>(this.records);
		Collections.shuffle(shuffled, new Random(randomSeed));

		int trainSize = (int) Math.round(shuffled.size() * trainRatio);
		// Ensure trainSize stays within valid split bounds [1, totalSize - 1]
		trainSize = Math.max(1, Math.min(trainSize, shuffled.size() - 1));

		List<Return3BSummaryBean> trainList = new ArrayList<>(shuffled.subList(0, trainSize));
		List<Return3BSummaryBean> testList = new ArrayList<>(shuffled.subList(trainSize, shuffled.size()));

		return new SplitResult(
				TrainingDataset.builder().records(trainList).build(),
				TrainingDataset.builder().records(testList).build()
		);
	}

	/**
	 * Constructs a native XGBoost {@link DMatrix} by flattening records into a
	 * contiguous row-major feature array and label array.
	 *
	 * @param targetExtractor Function extracting target variable $y$ (e.g., fraud flag or risk score)
	 * @return Initialized Native DMatrix instance (Caller MUST dispose of matrix memory via .dispose())
	 */
	public DMatrix toDMatrix(TargetExtractor targetExtractor) throws XGBoostError {
		Objects.requireNonNull(targetExtractor, "TargetExtractor function cannot be null");
		if (isEmpty()) {
			throw new IllegalStateException("Cannot construct DMatrix from empty dataset");
		}

		int numRows = records.size();
		int numCols = getFeatureNames().size();

		float[] featureData = new float[numRows * numCols];
		float[] labelData = new float[numRows];

		int dataIndex = 0;
		for (int r = 0; r < numRows; r++) {
			Return3BSummaryBean bean = records.get(r);
			if (bean == null) {
				bean = new Return3BSummaryBean();
			}
			Return3BSummaryBean.normalize(bean);

			float[] features = extractFeatureVector(bean);
			for (float val : features) {
				featureData[dataIndex++] = val;
			}

			labelData[r] = (float) targetExtractor.applyAsDouble(bean);
		}

		// Float.NaN marks missing entries for native C++ XGBoost handling
		DMatrix matrix = new DMatrix(featureData, numRows, numCols, Float.NaN);
		matrix.setLabel(labelData);

		log.debug("Constructed native DMatrix [Rows: {}, Cols: {}, Total Elements: {}]", 
				numRows, numCols, featureData.length);

		return matrix;
	}

	/**
	 * Flattens a single Return3BSummaryBean into an ordered 71-dimension numerical feature vector.
	 */
	public static float[] extractFeatureVector(Return3BSummaryBean bean) {
		if (bean == null) {
			bean = new Return3BSummaryBean();
		}
		bean.normalize();

		return new float[] {
				// 1. Filing & Basic Return Info
				toVal(bean.getFilingDelayDays()),

				// 2. Table 3.1(a) Outward Taxable Supplies
				toVal(bean.getTaxableValue()), toVal(bean.getOutputIgst()), toVal(bean.getOutputCgst()),
				toVal(bean.getOutputSgst()), toVal(bean.getOutputCess()), toVal(bean.getTotalOutputTax()),

				// 3. Table 3.1(b) Zero Rated
				toVal(bean.getZeroRatedValue()), toVal(bean.getZeroRatedIgst()), toVal(bean.getZeroRatedCgst()),
				toVal(bean.getZeroRatedSgst()), toVal(bean.getZeroRatedCess()),

				// 4. Table 3.1(c) Nil / Exempt
				toVal(bean.getNilExemptValue()), toVal(bean.getNilExemptIgst()), toVal(bean.getNilExemptCgst()),
				toVal(bean.getNilExemptSgst()), toVal(bean.getNilExemptCess()),

				// 5. Table 3.1(d) Reverse Charge (RCM)
				toVal(bean.getRcmTaxableValue()), toVal(bean.getRcmIgst()), toVal(bean.getRcmCgst()),
				toVal(bean.getRcmSgst()), toVal(bean.getRcmCess()), toVal(bean.getRcmTotalTax()),

				// 6. Table 3.1(e) Non-GST
				toVal(bean.getNonGstValue()), toVal(bean.getNonGstIgst()), toVal(bean.getNonGstCgst()),
				toVal(bean.getNonGstSgst()), toVal(bean.getNonGstCess()),

				// 7. Breakdown ITC (Import Goods, Services, Inward RCM, ISD, Others)
				toVal(bean.getItcImportGoodsIgst()), toVal(bean.getItcIsrcIgst()), toVal(bean.getItcOthIgst()),
				toVal(bean.getItcIsdIgst()), toVal(bean.getItcImpsIgst()),

				// 8. Net ITC Components
				toVal(bean.getNetItcIgst()), toVal(bean.getNetItcCgst()), toVal(bean.getNetItcSgst()),
				toVal(bean.getNetItcCess()), toVal(bean.getNetItcTotal()),

				// 9. Aggregated ITC Totals
				toVal(bean.getEligibleItc()), toVal(bean.getUtilizedItc()), toVal(bean.getReversedItc()),
				toVal(bean.getIneligibleItc()), toVal(bean.getExcessItc()),

				// 10. RCM & Cash Payments
				toVal(bean.getRcmPaymentTotal()), toVal(bean.getCashIgstPaid()), toVal(bean.getCashCgstPaid()),
				toVal(bean.getCashSgstPaid()), toVal(bean.getCashCessPaid()), toVal(bean.getCashTaxPaid()),

				// 11. ITC Set-off Total
				toVal(bean.getItcPaymentTotal()),

				// 12. Interest & Late Fees
				toVal(bean.getInterestPaid()), toVal(bean.getCalculatedInterest()), toVal(bean.getLateFeePaid()),
				toVal(bean.getLateFeeApplicable()), toVal(bean.getInterestApplicable()),

				// 13. E-Commerce Supplies
				toVal(bean.getEcommerceTurnover()), toVal(bean.getEcommerceRegisteredTurnover()),

				// 14. Derived Financial Ratios
				toVal(bean.getNilSupplyRatio()), toVal(bean.getItcUtilizationRatio()), toVal(bean.getItcToTaxRatio()),
				toVal(bean.getCashPaymentRatio()), toVal(bean.getItcPaymentRatio()), toVal(bean.getRcmToTaxRatio()),
				toVal(bean.getRcmItcRatio()), toVal(bean.getRcmCashRatio()),

				// 15. Historical 12-Month Benchmarks & Trends
				toVal(bean.getAvgTaxableValue12m()), toVal(bean.getAvgOutputTax12m()), toVal(bean.getAvgUtilizedItc12m()),
				toVal(bean.getAvgCashPaid12m()), toVal(bean.getTaxableValueTrendRatio()), toVal(bean.getOutputTaxTrendRatio()),
				toVal(bean.getTotalLateFilings12m()), toVal(bean.getLateFiled()),

				// 16. Existing Prediction Ensembles
				toVal(bean.getXgbRiskScore()), toVal(bean.getDl4jAnomalyScore()), toVal(bean.getPredictedOutputTax()),
				toVal(bean.getRiskScore())
		};
	}

	/* =========================================================
	   DEFENSIVE NULL-SAFE CONVERSION HELPERS
	   ========================================================= */

	private static float toVal(BigDecimal val) {
		return val != null ? val.floatValue() : 0.0f;
	}

	private static float toVal(Integer val) {
		return val != null ? val.floatValue() : 0.0f;
	}

	private static float toVal(Double val) {
		return val != null ? val.floatValue() : 0.0f;
	}

	private static float toVal(Boolean val) {
		return Boolean.TRUE.equals(val) ? 1.0f : 0.0f;
	}

	/**
	 * Gets ordered feature header names for matrix dimension validation and model diagnostics.
	 */
	public static List<String> getFeatureNames() {
		return Arrays.asList(
				// 1. Filing & Basic Return Info
				"filingDelayDays",

				// 2. Table 3.1(a) Outward Taxable Supplies
				"taxableValue", "outputIgst", "outputCgst", "outputSgst", "outputCess", "totalOutputTax",

				// 3. Table 3.1(b) Zero Rated
				"zeroRatedValue", "zeroRatedIgst", "zeroRatedCgst", "zeroRatedSgst", "zeroRatedCess",

				// 4. Table 3.1(c) Nil / Exempt
				"nilExemptValue", "nilExemptIgst", "nilExemptCgst", "nilExemptSgst", "nilExemptCess",

				// 5. Table 3.1(d) Reverse Charge (RCM)
				"rcmTaxableValue", "rcmIgst", "rcmCgst", "rcmSgst", "rcmCess", "rcmTotalTax",

				// 6. Table 3.1(e) Non-GST
				"nonGstValue", "nonGstIgst", "nonGstCgst", "nonGstSgst", "nonGstCess",

				// 7. Breakdown ITC
				"itcImportGoodsIgst", "itcIsrcIgst", "itcOthIgst", "itcIsdIgst", "itcImpsIgst",

				// 8. Net ITC Components
				"netItcIgst", "netItcCgst", "netItcSgst", "netItcCess", "netItcTotal",

				// 9. Aggregated ITC Totals
				"eligibleItc", "utilizedItc", "reversedItc", "ineligibleItc", "excessItc",

				// 10. RCM & Cash Payments
				"rcmPaymentTotal", "cashIgstPaid", "cashCgstPaid", "cashSgstPaid", "cashCessPaid", "cashTaxPaid",

				// 11. ITC Set-off Total
				"itcPaymentTotal",

				// 12. Interest & Late Fees
				"interestPaid", "calculatedInterest", "lateFeePaid", "lateFeeApplicable", "interestApplicable",

				// 13. E-Commerce Supplies
				"ecommerceTurnover", "ecommerceRegisteredTurnover",

				// 14. Derived Financial Ratios
				"nilSupplyRatio", "itcUtilizationRatio", "itcToTaxRatio", "cashPaymentRatio", "itcPaymentRatio",
				"rcmToTaxRatio", "rcmItcRatio", "rcmCashRatio",

				// 15. Historical 12-Month Benchmarks & Trends
				"avgTaxableValue12m", "avgOutputTax12m", "avgUtilizedItc12m", "avgCashPaid12m",
				"taxableValueTrendRatio", "outputTaxTrendRatio", "totalLateFilings12m", "lateFiled",

				// 16. Existing Prediction Ensembles
				"xgbRiskScore", "dl4jAnomalyScore", "predictedOutputTax", "riskScore"
		);
	}
}