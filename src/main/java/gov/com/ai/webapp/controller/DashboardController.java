package gov.com.ai.webapp.controller;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import gov.com.ai.webapp.model.DashboardDTOs.DashboardMetricsResponse;
import gov.com.ai.webapp.model.GstRiskSummaryDto;
import gov.com.ai.webapp.model.ReturnPeriodOptionDto;
import gov.com.ai.webapp.service.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/gst/return-3b")
@RequiredArgsConstructor
@Validated
@CrossOrigin(origins = "*")
public class DashboardController {

	private final DashboardService dashboardService;

	@GetMapping("/metrics")
	public ResponseEntity<DashboardMetricsResponse> getDashboardMetrics(
			@RequestParam(name = "retPeriod", required = false) String retPeriod,
			@RequestParam(name = "stateCode", required = false) String stateCode,
			@RequestParam(name = "forceRefresh", defaultValue = "false") boolean forceRefresh) {
		
		log.info("Request received for dashboard analytics. Period: {}, State: {}", retPeriod, stateCode);

		DashboardMetricsResponse metrics = dashboardService.getAggregatedMetrics(retPeriod, stateCode, forceRefresh);

		CacheControl cacheControl = forceRefresh ? CacheControl.noCache()
				: CacheControl.maxAge(60, TimeUnit.SECONDS).cachePublic();

		return ResponseEntity.ok().cacheControl(cacheControl).body(metrics);
	}

	@PostMapping("/refresh-cache")
	public ResponseEntity<Void> refreshAnalyticsCache() {
		log.info("Evicting analytics cache...");
		dashboardService.evictAndRebuildCache();
		return ResponseEntity.accepted().build();
	}
	
	@GetMapping("/periods")
    public ResponseEntity<List<ReturnPeriodOptionDto>> getAllReturnPeriods() {
        log.info("Fetching all available return periods");
     
        List<ReturnPeriodOptionDto> periods = dashboardService.getAllAvailableReturnPeriods();
        return ResponseEntity.ok(periods);
    }
	
	/**
     * GET /gst/return-3b/summary
     * Fetches detailed GSTR-3B risk and anomaly records for a specific period.
     * Supports optional search filtering and pagination.
     */
    @GetMapping("/summary")
    public ResponseEntity<List<GstRiskSummaryDto>> getRiskSummary(
            @RequestParam(name = "retPeriod") String retPeriod,
            @RequestParam(name = "gstin", required = false) String gstin) {
        
        log.info("Fetching GST risk summary for period: {}, gstin filter: {}", retPeriod, gstin);
        List<GstRiskSummaryDto> records = dashboardService.getRiskSummary(retPeriod);
        return ResponseEntity.ok(records);
    }

}
