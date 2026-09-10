package gov.com.ai.webapp.controller.dto;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import gov.com.ai.webapp.model.dto.GstAnalyticsResponse;
import gov.com.ai.webapp.service.dto.GstAnalyticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/gst/analytics")
@RequiredArgsConstructor
@Validated
@CrossOrigin(origins = "*")
public class GstAnalyticsController {

	private final GstAnalyticsService analyticsService;

	@GetMapping("/{gstin}")
	public ResponseEntity<GstAnalyticsResponse> getAnalytics(@PathVariable String gstin) {
       log.info("Enter into getAnalytics:--"+gstin);
		return ResponseEntity.ok(analyticsService.getAnalytics(gstin));
	}
}
