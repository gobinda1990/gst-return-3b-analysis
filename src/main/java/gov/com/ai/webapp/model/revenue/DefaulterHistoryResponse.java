package gov.com.ai.webapp.model.revenue;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DefaulterHistoryResponse {

    private String gstin;
    private String retPeriod;

    private LocalDate dueDate;
    private LocalDate filingDate;

    private String filingStatus;
    private Integer delayDays;

    private BigDecimal taxableValue;
    private BigDecimal outputTax;

    private String defaultLevel;
    private BigDecimal defaultScore;

    private String riskLevel;
    private BigDecimal riskScore;

    private String gstr3aEligible;
    private String gstr3aStatus;

    private String section62Candidate;
    private String officerReviewStatus;
}
