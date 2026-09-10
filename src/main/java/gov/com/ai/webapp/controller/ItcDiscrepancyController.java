package gov.com.ai.webapp.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import gov.com.ai.webapp.model.ItcDiscrepancyResponse;
import gov.com.ai.webapp.service.ItcDiscrepancyEngine;

@RestController
@RequestMapping("/api/v1/gst/itc")
@RequiredArgsConstructor
public class ItcDiscrepancyController {

	private final ItcDiscrepancyEngine engine;

	@GetMapping("/discrepancy/{gstin}")
	public ResponseEntity<ItcDiscrepancyResponse> getDiscrepancy(@PathVariable String gstin) {

		return ResponseEntity.ok(engine.analyze(gstin.toUpperCase()));
	}
}
