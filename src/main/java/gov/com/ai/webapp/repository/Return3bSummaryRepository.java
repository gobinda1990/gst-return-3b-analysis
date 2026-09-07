package gov.com.ai.webapp.repository;

import java.util.List;
import java.util.Optional;
import gov.com.ai.webapp.model.DashboardDTOs.ComplianceAlertDTO;
import gov.com.ai.webapp.model.DashboardDTOs.ForecastSeriesDTO;
import gov.com.ai.webapp.model.DashboardDTOs.FraudSeriesDTO;
import gov.com.ai.webapp.model.DashboardDTOs.HighRiskGstinDTO;
import gov.com.ai.webapp.model.DashboardDTOs.TaxCollectionSummaryDTO;


public interface Return3bSummaryRepository {
	
   
	
	Optional<SummaryKPIs> fetchAggregatedKPIs(String retPeriod, String stateCode);

	List<HighRiskGstinDTO> fetchTopHighRiskGstins(String retPeriod, String stateCode, int limit);

	List<ComplianceAlertDTO> fetchRecentComplianceAlerts(String retPeriod, String stateCode, int limit);

	List<ForecastSeriesDTO> fetchForecastSeries(String stateCode, int monthsHistory);

	List<FraudSeriesDTO> fetchFraudSeries(String stateCode, int monthsHistory);
	
	//
	
	Optional<SummaryKPIs> fetchAggregatedKPIs(String gstin, String retPeriod, String stateCode);

    List<HighRiskGstinDTO> fetchTopHighRiskGstins(String gstin, String retPeriod, String stateCode, int limit);

    List<ComplianceAlertDTO> fetchRecentComplianceAlerts(String gstin, String retPeriod, String stateCode, int limit);

    List<ForecastSeriesDTO> fetchForecastSeries(String gstin, String stateCode, int monthsHistory);

    List<FraudSeriesDTO> fetchFraudSeries(String gstin, String stateCode, int monthsHistory);
    
    List<TaxCollectionSummaryDTO> fetchTaxCollectionSummary(String gstin, String retPeriod, String stateCode, int monthsHistory);

	record SummaryKPIs(Long totalGstins, java.math.BigDecimal totalTaxableValue, java.math.BigDecimal totalCashPaid,
			java.math.BigDecimal totalUtilizedItc, java.math.BigDecimal totalExcessItc,
			java.math.BigDecimal totalRcmTax, java.math.BigDecimal totalEcommerceTurnover, Double avgCashPaymentRatio,
			Double avgItcUtilizationRatio, Double avgXgbRiskScore, Double avgFilingDelayDays, Long fraudAlertsCount,
			Long predictedDefaultsCount) {
	}

}
