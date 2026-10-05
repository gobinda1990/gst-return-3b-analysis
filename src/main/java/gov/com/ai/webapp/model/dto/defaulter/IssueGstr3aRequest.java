package gov.com.ai.webapp.model.dto.defaulter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record IssueGstr3aRequest(
    @NotBlank
    @Pattern(regexp = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$", message = "Invalid GSTIN")
    String gstin,
    @NotBlank
    @Pattern(regexp = "^(0[1-9]|1[0-2])\\d{4}$", message = "Return period must be MMYYYY")
    String retPeriod,
    @Size(max = 1000)
    String remarks
) {}
