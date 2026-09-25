package gov.com.ai.webapp.controller.dto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import gov.com.ai.webapp.model.dto.GstItcRiskRow;
import gov.com.ai.webapp.service.dto.GstItcBulkAnalyticsService;
import java.util.List;

@RestController
@RequestMapping("/gst/return-3b")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:5173")
@Slf4j
public class GstItcAuditController {

	private final GstItcBulkAnalyticsService service;	

	@GetMapping("/scrutiny-pipeline")
	public ResponseEntity<List<GstItcRiskRow>> getItcDiscrepancy(@RequestParam String retPeriod) {	

        log.info("hello");
		return ResponseEntity.ok(service.analyze(retPeriod.toUpperCase()));
	}
}
