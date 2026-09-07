package gov.com.ai.webapp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

public class GstMlDto {

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class PredictionRequest {
		private String gstin;
		private int forecastPeriods;
	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class GstinFeatureVector {
		private String gstin;
		private float avgOutputTaxLakhs;
		private float avgItcLakhs;
		private float avgCashTaxLakhs;
		private float avgDelayDays;
		private float gstr1Vs3bMismatchRatio;
		private float gstr2bVs3bMismatchRatio;

		public float[] toFloatArray() {
			return new float[] { avgOutputTaxLakhs, avgItcLakhs, avgCashTaxLakhs, avgDelayDays, gstr1Vs3bMismatchRatio,
					gstr2bVs3bMismatchRatio };
		}
	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class PredictionResponse {
		private String gstin;
		private String predictionPeriod;
		private String targetRetPeriod; // React Compatibility Mapping
		private String riskCategory;
		private String riskTrend; // React Compatibility Mapping ("HIGH", "MEDIUM", "LOW")
		private Double lateFilingRiskScore;
		private Double probabilityOfDefault; // React Compatibility Mapping
		private Boolean defaultPrediction;
		private Boolean defaultPredicted; // React Compatibility Mapping
		private String predictionSource;
		private String modelVersion;
		private String primaryRiskFactor;
		private String dueDate;
		private String estimatedFilingDate;
		private Integer delayDays;
		private Double calculatedLateFee;
		private String calculatedDt;

		// Top-Level Tax Summary Fields (Direct Mapping for UI Engine)
		private Double predictedTaxableVal;
		private Double predictedOutputTax;
		private Double predictedItcAvail;
		private Double predictedItcRatio;

		private List<CashLiabilityForecast> forecastedCashLiability;
	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class CashLiabilityForecast {
		private String returnPeriod;
		private Double predictedTaxValue;
		private Double predictedItcValue;
		private Double predictedCashValue;
	}
}