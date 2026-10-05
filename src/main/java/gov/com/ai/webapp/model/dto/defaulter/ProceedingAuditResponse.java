package gov.com.ai.webapp.model.dto.defaulter;

import java.time.LocalDateTime;

public record ProceedingAuditResponse(
        Long id,
        Long proceedingId,
        String gstin,
        String retPeriod,
        String actionType,
        String oldStatus,
        String newStatus,
        String referenceNo,
        String officerHrms,
        String remarks,
        LocalDateTime actionAt
) {}
