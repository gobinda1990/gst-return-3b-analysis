package gov.com.ai.webapp.controller;

import jakarta.validation.constraints.Pattern;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import gov.com.ai.webapp.model.GstAuditRecord;
import gov.com.ai.webapp.model.PagedAuditResponse;
import gov.com.ai.webapp.service.GstAuditJdbcService;

@Slf4j
@RestController
//@RequestMapping("/gst/return-3b")
@Validated
@CrossOrigin(origins = "*")
public class GstAuditJdbcController {

	private final GstAuditJdbcService service;

	public GstAuditJdbcController(GstAuditJdbcService service) {
		this.service = service;
	}

	@GetMapping("/scrutiny-pipeline")
	public ResponseEntity<PagedAuditResponse<GstAuditRecord>> getScrutinyPipeline(
			@RequestParam(name = "retPeriod") @Pattern(regexp = "^\\d{6}$", message = "retPeriod must be in MMYYYY format") String retPeriod,

			@RequestParam(name = "page", defaultValue = "0") int page,
			@RequestParam(name = "size", defaultValue = "10") int size) {
		log.info("Enter into:");
		PagedAuditResponse<GstAuditRecord> response = service.getAuditPipeline(retPeriod, page, size);
		return ResponseEntity.ok(response);
	}
}
