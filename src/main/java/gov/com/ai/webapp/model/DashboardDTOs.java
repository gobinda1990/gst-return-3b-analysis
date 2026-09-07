package gov.com.ai.webapp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.util.List;

public class DashboardDTOs {
	
	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class GstinAnalysisResponse {
	    private String gstin;
	    private String legalName;
	    private String tradeName;
	    private String pan;
	    private String jurisdiction;
	    private String taxpayerType;
	    private String status;
	    
	    private BigDecimal lifetimeOutputTax;
	    private BigDecimal lifetimeIgst;
	    private BigDecimal lifetimeCgst;
	    private BigDecimal lifetimeSgst;
	    private BigDecimal lifetimeCess;
	    private BigDecimal lifetimeRcmTax;
	    private BigDecimal lifetimeExcessItc;
	    private Double itcUtilizationRatio;
	    private Double cashPaymentRatio;
	    private Integer totalReturns;
	    private Integer delayedReturns;
	    private Integer pendingReturns;
	    private Integer filedReturns;
	    private Integer maxFilingDelayDays;

	    // Overall Profile Metrics
	    private BigDecimal lifetimeTaxableValue;
	    private BigDecimal lifetimeCashPaid;
	    private BigDecimal lifetimeItcUtilized;
	    private Double currentRiskScore;
	    private String riskCategory;

	    // 6-Month Historical Returns
	    private List<Gstr3bMonthlyReturnDTO> last6MonthsHistory;

	    // High-Risk Anomaly Flags
	    private List<ComplianceAlertDTO> activeAlerts;
	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Gstr3bMonthlyReturnDTO {
	    private String retPeriod;         // e.g., "032026"
	    private String formattedPeriod;    // e.g., "Mar 2026"
	    private BigDecimal taxableValue;
	    private BigDecimal igst;
	    private BigDecimal cgst;
	    private BigDecimal sgst;
	    private BigDecimal cess;
	    private BigDecimal outputTax;
	    private BigDecimal itcClaimed;
	    private BigDecimal cashPaid;
	    private BigDecimal rcmTax;
	    private Double itcRatio;
	    private Double cashRatio;
	    private Integer filingDelayDays;
	    private String filingStatus;       // "FILED", "DELAYED", "PENDING"
	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class DashboardMetricsResponse {
		private boolean riskModelLoaded;
		private String riskModelVersion;
		private boolean forecastModelLoaded;
		private String forecastModelVersion;

		// Core Summary Metrics
		private Long totalGstins;
		private BigDecimal totalTaxableValue;
		private BigDecimal totalCashPaid;
		private BigDecimal totalUtilizedItc;
		private BigDecimal totalExcessItc;
		private BigDecimal totalRcmTax;
		private BigDecimal totalEcommerceTurnover;

		// Aggregated Ratios & Indicators
		private Double avgCashPaymentRatio;
		private Double avgItcUtilizationRatio;
		private Double avgXgbRiskScore;
		private Double avgFilingDelayDays;

		// Counts
		private Long fraudAlertsCount;
		private Long predictedDefaultsCount;

		// Series & Lists
		private List<ForecastSeriesDTO> forecastSeries;
		private List<FraudSeriesDTO> fraudSeries;
		private List<HighRiskGstinDTO> topHighRiskGstins;
		private List<ComplianceAlertDTO> recentAlerts;
		private List<TaxCollectionSummaryDTO> taxsummary;
	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ForecastSeriesDTO {
		private String period;
		private BigDecimal actualOutputTax;
		private BigDecimal predictedOutputTax;
		private BigDecimal cashInflow;
	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class FraudSeriesDTO {
		private String period;
		private BigDecimal totalItcClaimed;
		private BigDecimal flaggedExcessItc;
		private Long anomalyCount;
	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class HighRiskGstinDTO {
		private String gstin;
		private String retPeriod;
		private String stateCode;
		private BigDecimal taxableValue;
		private Double itcUtilizationRatio;
		private Double cashPaymentRatio;
		private Integer filingDelayDays;
		private Double xgbRiskScore;
		private Double dl4jAnomalyScore;
		private String riskCategory;
	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ComplianceAlertDTO {
		private String id;
		private String gstin;
		private String retPeriod;
		private String message;
		private BigDecimal excessItc;
		private String formattedDate;
		private String severity;
	}
	
	/* =========================================================
	   TAX COLLECTION SUMMARY DTO
	   Period-wise IGST, CGST, SGST, CESS and cash payment breakdown
	   ========================================================= */
	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class TaxCollectionSummaryDTO {
		private String period;                  // e.g., "032026" (March 2026)
		
		// Tax Components Collected
		private BigDecimal igst;               // Integrated GST collected
		private BigDecimal cgst;               // Central GST collected
		private BigDecimal sgst;               // State GST collected
		private BigDecimal cess;               // Cess collected
		private BigDecimal totalTax;           // Sum of IGST + CGST + SGST + CESS
		
		// Cash Payments by Tax Component
		private BigDecimal cashIgstPaid;       // IGST cash paid
		private BigDecimal cashCgstPaid;       // CGST cash paid
		private BigDecimal cashSgstPaid;       // SGST cash paid
		private BigDecimal cashCessPaid;       // CESS cash paid
		private BigDecimal cashTaxPaid;        // Total cash paid
		
		// Payment Analysis
		private Double paymentRatioPercent;    // (cashTaxPaid / totalTax) * 100
	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ApiErrorResponse {
		private int status;
		private String error;
		private String message;
		private long timestamp;
	}
}
