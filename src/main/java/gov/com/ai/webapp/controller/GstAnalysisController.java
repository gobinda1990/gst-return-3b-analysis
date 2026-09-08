package gov.com.ai.webapp.controller;

import gov.com.ai.webapp.model.GstMonthlySummaryDto;
import gov.com.ai.webapp.service.GSTFinancialYearService;
import gov.com.ai.webapp.service.GstAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/gst/return-3b")
@CrossOrigin(origins = "*") 
@RequiredArgsConstructor
public class GstAnalysisController {

    private final GstAnalysisService gstAnalysisService;
    
    private final GSTFinancialYearService gstFinancialYearService;

    /**
     * Fetches the list of available financial years for dropdown selections.
     * Endpoint: GET /gst/return-3b/fin-year
     */
    @GetMapping("/fin-year")
    public ResponseEntity<List<String>> getFinYear() {
        log.debug("REST request to fetch GST Financial Years list");
        List<String> finYears = gstFinancialYearService.getFinancialYearDropdownList();
        log.info("Successfully fetched {} financial year records", finYears != null ? finYears.size() : 0);
        return ResponseEntity.ok(finYears);
    }
   
    @GetMapping("/monthly-summary")
    public ResponseEntity<List<GstMonthlySummaryDto>> getMonthlySummary(@RequestParam(value = "finYear", required = false) String finYear) {

        log.info("REST request to fetch GSTR-3B monthly summary for finYear: '{}'", finYear);

        List<GstMonthlySummaryDto> summaryList = gstAnalysisService.getMonthlyRevenueSummary(finYear);

        log.info("Successfully retrieved {} monthly summary records for finYear: '{}'", 
                summaryList != null ? summaryList.size() : 0, finYear);

        return ResponseEntity.ok(summaryList);
    }
}