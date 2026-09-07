package gov.com.ai.webapp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReturnDefaulterDTO {

    private String gstin;
    private String retPeriod;
    private Integer filingDelayDays;
    private BigDecimal taxableValue;
    private BigDecimal totalOutputTax;
    private BigDecimal xgbRiskScore;
    private String statutoryActionRequired;
    private String defaulterRiskLevel;
}
