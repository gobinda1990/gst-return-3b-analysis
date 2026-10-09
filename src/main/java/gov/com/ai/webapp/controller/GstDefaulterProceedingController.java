package gov.com.ai.webapp.controller;

import gov.com.ai.webapp.model.dto.defaulter.*;
import gov.com.ai.webapp.service.defaulter.DefaulterProceedingQueryService;
import gov.com.ai.webapp.service.defaulter.Gstr3aNoticeService;
import gov.com.ai.webapp.service.defaulter.JurisdictionAuthorizationService;
import gov.com.ai.webapp.service.defaulter.Section62AssessmentService;
import gov.com.ai.webapp.service.defaulter.Section62EligibilityService;
import gov.com.ai.webapp.service.defaulter.Section62ReconciliationService;
import gov.com.ai.webapp.util.JwtUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Slf4j
@Validated
@RestController
@RequestMapping("/gst/return-3b/defaulters/proceedings")
@RequiredArgsConstructor
public class GstDefaulterProceedingController {

	private static final String PERIOD_PATTERN = "^(0[1-9]|1[0-2])\\d{4}$";

	private final Gstr3aNoticeService gstr3aNoticeService;
	private final Section62EligibilityService section62EligibilityService;
	private final Section62AssessmentService assessmentService;
	private final Section62ReconciliationService reconciliationService;
	private final DefaulterProceedingQueryService queryService;
	private final JurisdictionAuthorizationService authorizationService;
	private final JwtUtil jwtUtil;

	/* ------------------------------- QUERIES -------------------------------- */

	@GetMapping("/summary")
	public ResponseEntity<ProceedingSummaryResponse> summary(
			@RequestParam(required = false) @Pattern(regexp = PERIOD_PATTERN) String retPeriod,
			@RequestParam(required = false) String status, @RequestParam(required = false) String search) {
		return ResponseEntity.ok(queryService.summary(retPeriod, status, search));
	}

	@GetMapping
	public ResponseEntity<ProceedingPageResponse<ProceedingRowResponse>> proceedings(
			@RequestParam(required = false) @Pattern(regexp = PERIOD_PATTERN) String retPeriod,
			@RequestParam(required = false) String status, @RequestParam(required = false) String search,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
		return ResponseEntity.ok(queryService.find(retPeriod, status, search, page, size));
	}

	@GetMapping("/{proceedingId}")
	public ResponseEntity<ProceedingRowResponse> proceeding(@PathVariable @Min(1) Long proceedingId) {
		return ResponseEntity.ok(queryService.findById(proceedingId));
	}

	@GetMapping("/{proceedingId}/audit")
	public ResponseEntity<List<ProceedingAuditResponse>> audit(@PathVariable @Min(1) Long proceedingId) {
		return ResponseEntity.ok(queryService.findAudit(proceedingId));
	}

	/* ------------------------------- ACTIONS -------------------------------- */

	@PostMapping("/gstr3a")
	public ResponseEntity<Gstr3aResponse> issueGstr3a(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody IssueGstr3aRequest request) {

		String hrmsCode = requireHrmsCode(jwt);

		List<String> officeIds = authorizationService.resolveOfficeIds(hrmsCode);

		if (officeIds.isEmpty()) {
			log.warn("GSTR-3A issuance denied: no offices assigned hrms={}", hrmsCode);
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No offices assigned to the officer");
		}

		return ResponseEntity.ok(gstr3aNoticeService.issue(request, hrmsCode, officeIds));
	}

	@PostMapping("/section62/scan")
	public ResponseEntity<?> scanSection62(@AuthenticationPrincipal Jwt jwt) {
		requireHrmsCode(jwt);
		return ResponseEntity.ok(section62EligibilityService.scan());
	}

//	@PostMapping("/asmt13")
//	public ResponseEntity<?> issueAsmt13(@AuthenticationPrincipal Jwt jwt,
//			@Valid @RequestBody IssueAsmt13Request request) {
//		return ResponseEntity.ok(assessmentService.issue(request, requireHrmsCode(jwt)));
//	}

	@PostMapping("/{proceedingId}/reconcile")
	public ResponseEntity<?> reconcile(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) Long proceedingId) {
		return ResponseEntity.ok(reconciliationService.reconcile(proceedingId, requireHrmsCode(jwt)));
	}

	/* ------------------------------- HELPERS -------------------------------- */

	private String requireHrmsCode(Jwt jwt) {

		if (jwt == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
		}

		String hrmsCode = jwtUtil.getHrmsCode(jwt);

		if (hrmsCode == null || hrmsCode.isBlank()) {
			log.warn("Request rejected: no HRMS code in token");
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "HRMS code missing in token");
		}

		return hrmsCode.trim();
	}
}