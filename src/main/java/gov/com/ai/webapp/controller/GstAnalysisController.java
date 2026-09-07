package gov.com.ai.webapp.controller;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import gov.com.ai.webapp.model.GstMonthlySummaryDto;
import gov.com.ai.webapp.service.GstAnalysisService;

import java.util.List;

@RestController
@RequestMapping("/gst/return-3b")
@CrossOrigin(origins = "*") // Adjust CORS origins as needed
public class GstAnalysisController {

    @Autowired
    private GstAnalysisService service;

    @GetMapping("/monthly-summary")
    public ResponseEntity<List<GstMonthlySummaryDto>> getMonthlySummary(
            @RequestParam(value = "periods", required = false) List<String> periods) {
        return ResponseEntity.ok(service.getMonthlyRevenueSummary(periods));
    }
}
