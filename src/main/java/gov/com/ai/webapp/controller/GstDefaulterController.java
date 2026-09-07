package gov.com.ai.webapp.controller;



import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import gov.com.ai.webapp.model.GstDefaulterRecord;
import gov.com.ai.webapp.model.PagedAuditResponse;
import gov.com.ai.webapp.service.ReturnDefaulterService;

@RestController
@RequestMapping("/gst/return-3b")
@RequiredArgsConstructor
@Validated
@CrossOrigin(origins = "*")
public class GstDefaulterController {

    private final ReturnDefaulterService service;

    /**
     * Fetches GST 3A / REG-17 Return Defaulter Action Engine pipeline data.
     *
     * @param retPeriod Return period in MMYYYY format (e.g. "032026")
     * @param page Zero-indexed page number (default 0)
     * @param size Number of records per page (default 10)
     * @return PagedAuditResponse containing defaulter records and metadata
     */
    @GetMapping("/defaulters")
    public ResponseEntity<PagedAuditResponse<GstDefaulterRecord>> getDefaulterPipeline(
            @RequestParam(name = "retPeriod") 
            @Pattern(regexp = "^\\d{6}$", message = "retPeriod must be in MMYYYY format") String retPeriod,

            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size) {
            
        PagedAuditResponse<GstDefaulterRecord> response = service.getDefaulterPipeline(retPeriod, page, size);
        return ResponseEntity.ok(response);
    }
}
