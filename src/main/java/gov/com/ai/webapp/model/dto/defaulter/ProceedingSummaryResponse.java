package gov.com.ai.webapp.model.dto.defaulter;

public record ProceedingSummaryResponse(
        long total,
        long openProceedings,
        long gstr3aEligible,
        long gstr3aCompliancePending,
        long section62Eligible,
        long asmt13Issued
) {}
