package gov.com.ai.webapp.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import gov.com.ai.webapp.model.DashboardDTOs.GstinAnalysisResponse;
import gov.com.ai.webapp.service.Return3bAnalysisService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/gst/return-3b")
@CrossOrigin(origins = "*")
@Slf4j
@RequiredArgsConstructor
public class Return3bAnalysisController {
	
	private final Return3bAnalysisService return3bAnalysisService;
	
	@GetMapping("/analytics/{gstin}")
	public ResponseEntity<GstinAnalysisResponse> getGstinAnalysis(
			@PathVariable @Pattern(regexp = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$", message = "Invalid GSTIN format provided") String gstin) {

		log.info("Fetching 6-month GSTR-3B analytics breakdown for GSTIN: {}", gstin);

		GstinAnalysisResponse response = return3bAnalysisService.getGstinAnalysis(gstin.toUpperCase());
		log.info("Tax val:--{}",response.getLifetimeItcEligible());
		return ResponseEntity.ok(response);
	}

}
