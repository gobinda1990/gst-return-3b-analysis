package gov.com.ai.webapp.service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import gov.com.ai.webapp.model.DashboardDTOs.DashboardMetricsResponse;
import gov.com.ai.webapp.model.GstRiskSummaryDto;
import gov.com.ai.webapp.model.ReturnPeriodOptionDto;
import gov.com.ai.webapp.repository.Return3bRepository;
import gov.com.ai.webapp.repository.Return3bSummaryRepository;
import gov.com.ai.webapp.repository.Return3bSummaryRepository.SummaryKPIs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

	private final Return3bSummaryRepository return3bSummaryRepository;
	
	private final Return3bRepository return3bRepository;

	@Override
	@Transactional(readOnly = true)
//	@Cacheable(value = "dashboard_metrics", key = "(#retPeriod ?: 'ALL') + '_' + (#stateCode ?: 'ALL')", condition = "!#forceRefresh")
	public DashboardMetricsResponse getAggregatedMetrics(String retPeriod, String stateCode, boolean forceRefresh) {
		log.info("GSTR-3B dashboard analytics. Period: {}, State: {}, ForceRefresh: {}", retPeriod,
				stateCode, forceRefresh);

		// Fetch aggregates safely with default fallbacks
		SummaryKPIs kpis = return3bSummaryRepository.fetchAggregatedKPIs(retPeriod, stateCode)
				.orElse(new SummaryKPIs(0L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
						BigDecimal.ZERO, BigDecimal.ZERO, 0.0, 0.0, 0.0, 0.0, 0L, 0L));

		var topRisks = return3bSummaryRepository.fetchTopHighRiskGstins(retPeriod, stateCode, 10);
		var alerts = return3bSummaryRepository.fetchRecentComplianceAlerts(retPeriod, stateCode, 5);
		var forecastSeries = return3bSummaryRepository.fetchForecastSeries(stateCode, 6);
		var fraudSeries = return3bSummaryRepository.fetchFraudSeries(stateCode, 6);
		var taxsum = return3bSummaryRepository.fetchTaxCollectionSummary(stateCode, retPeriod, stateCode, 0);
		// Reverse series to ensure chronological left-to-right charting order
		Collections.reverse(forecastSeries);
		Collections.reverse(fraudSeries);

		return DashboardMetricsResponse.builder().riskModelLoaded(true).riskModelVersion("xgb-v2.4.1-prod")
				.forecastModelLoaded(true).forecastModelVersion("dl4j-lstm-v1.2")

				.totalGstins(kpis.totalGstins()).totalTaxableValue(kpis.totalTaxableValue())
				.totalCashPaid(kpis.totalCashPaid()).totalUtilizedItc(kpis.totalUtilizedItc())
				.totalExcessItc(kpis.totalExcessItc()).totalRcmTax(kpis.totalRcmTax())
				.totalEcommerceTurnover(kpis.totalEcommerceTurnover())

				.avgCashPaymentRatio(kpis.avgCashPaymentRatio()).avgItcUtilizationRatio(kpis.avgItcUtilizationRatio())
				.avgXgbRiskScore(kpis.avgXgbRiskScore()).avgFilingDelayDays(kpis.avgFilingDelayDays())

				.fraudAlertsCount(kpis.fraudAlertsCount()).predictedDefaultsCount(kpis.predictedDefaultsCount())

				.topHighRiskGstins(topRisks).recentAlerts(alerts).forecastSeries(forecastSeries)
				.fraudSeries(fraudSeries).taxsummary(taxsum).build();
	}

	@Override
	@CacheEvict(value = "dashboard_metrics", allEntries = true)
	public void evictAndRebuildCache() {
		log.info("Analytics dashboard cache evicted successfully.");

	}

	@Override
	public List<ReturnPeriodOptionDto> getAllAvailableReturnPeriods() {		
		return return3bRepository.getAllAvailableReturnPeriods();
	}

	@Override
	public List<GstRiskSummaryDto> getRiskSummary(String retPeriod) {		
		return return3bRepository.getRiskSummary(retPeriod);
	}

}
