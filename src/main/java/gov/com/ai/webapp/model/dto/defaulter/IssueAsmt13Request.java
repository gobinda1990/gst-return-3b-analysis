package gov.com.ai.webapp.model.dto.defaulter;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record IssueAsmt13Request(
    @NotNull Long proceedingId,
    @NotNull @DecimalMin("0.00") BigDecimal assessedTax,
    @NotNull @DecimalMin("0.00") BigDecimal assessedInterest,
    @NotNull @DecimalMin("0.00") BigDecimal assessedPenalty,
    @NotNull @DecimalMin("0.00") BigDecimal assessedLateFee,
    @NotNull @DecimalMin("0.00") BigDecimal assessedOther,
    @NotBlank @Size(min = 10, max = 4000) String findings
) {}
