package gov.com.ai.webapp.service;

import java.util.List;

import gov.com.ai.webapp.model.DashboardDTOs.DashboardMetricsResponse;
import gov.com.ai.webapp.model.GstRiskSummaryDto;
import gov.com.ai.webapp.model.ReturnPeriodOptionDto;

public interface DashboardService {

	DashboardMetricsResponse getAggregatedMetrics(String retPeriod, String stateCode, boolean forceRefresh);
	
	List<ReturnPeriodOptionDto> getAllAvailableReturnPeriods();
	
    List<GstRiskSummaryDto> getRiskSummary(String retPeriod);

	void evictAndRebuildCache();

}
