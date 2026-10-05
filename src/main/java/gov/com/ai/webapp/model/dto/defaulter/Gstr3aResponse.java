package gov.com.ai.webapp.model.dto.defaulter;

import java.time.LocalDate;

public record Gstr3aResponse(
    Long proceedingId,
    String gstin,
    String retPeriod,
    String status,
    String referenceNo,
    LocalDate issueDate,
    LocalDate complianceDeadline,
    String legalProvision
) {}
