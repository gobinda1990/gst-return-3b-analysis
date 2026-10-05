package gov.com.ai.webapp.controller;

import gov.com.ai.webapp.model.dto.defaulter.*;
import gov.com.ai.webapp.service.defaulter.DefaulterProceedingQueryService;
import gov.com.ai.webapp.service.defaulter.Gstr3aNoticeService;
import gov.com.ai.webapp.service.defaulter.Section62AssessmentService;
import gov.com.ai.webapp.service.defaulter.Section62EligibilityService;
import gov.com.ai.webapp.service.defaulter.Section62ReconciliationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
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

    @GetMapping("/summary")
//    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ProceedingSummaryResponse> summary(
            @RequestParam(required=false) @Pattern(regexp=PERIOD_PATTERN) String retPeriod,
            @RequestParam(required=false) String status,
            @RequestParam(required=false) String search) {
        return ResponseEntity.ok(queryService.summary(retPeriod,status,search));
    }

    @GetMapping
//    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ProceedingPageResponse<ProceedingRowResponse>> proceedings(
            @RequestParam(required=false) @Pattern(regexp=PERIOD_PATTERN) String retPeriod,
            @RequestParam(required=false) String status,
            @RequestParam(required=false) String search,
            @RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="25") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(queryService.find(retPeriod,status,search,page,size));
    }

    @GetMapping("/{proceedingId}")
//    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ProceedingRowResponse> proceeding(@PathVariable @Min(1) Long proceedingId) {
        return ResponseEntity.ok(queryService.findById(proceedingId));
    }

    @GetMapping("/{proceedingId}/audit")
//    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ProceedingAuditResponse>> audit(@PathVariable @Min(1) Long proceedingId) {
        return ResponseEntity.ok(queryService.findAudit(proceedingId));
    }

    @PostMapping("/gstr3a")
//    @PreAuthorize("hasAuthority('GST_GSTR3A_ISSUE')")
    public ResponseEntity<?> issueGstr3a(@Valid @RequestBody IssueGstr3aRequest request) {
        return ResponseEntity.ok(gstr3aNoticeService.issue(request, null));
    }

    @PostMapping("/section62/scan")
//    @PreAuthorize("hasAuthority('GST_SECTION62_SCAN')")
    public ResponseEntity<?> scanSection62() {
        return ResponseEntity.ok(section62EligibilityService.scan());
    }

    @PostMapping("/asmt13")
//    @PreAuthorize("hasAuthority('GST_SECTION62_ASSESS')")
    public ResponseEntity<?> issueAsmt13(@Valid @RequestBody IssueAsmt13Request request) {
        return ResponseEntity.ok(assessmentService.issue(request, null));
    }

    @PostMapping("/{proceedingId}/reconcile")
//    @PreAuthorize("hasAuthority('GST_SECTION62_RECONCILE')")
    public ResponseEntity<?> reconcile(@PathVariable @Min(1) Long proceedingId) {
        return ResponseEntity.ok(reconciliationService.reconcile(proceedingId,null));
    }

//    private String officer(Authentication auth) {
//        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
//            throw new org.springframework.security.access.AccessDeniedException("Authenticated officer required");
//        }
//        return auth.getName().trim();
//    }
}
