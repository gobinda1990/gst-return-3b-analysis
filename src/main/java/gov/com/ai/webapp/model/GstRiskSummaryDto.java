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
public class GstRiskSummaryDto {
    private String gstin;
    private String retPeriod;
    private BigDecimal taxableValue;
    private BigDecimal totalOutputTax;
    private BigDecimal eligibleItc;
    private BigDecimal utilizedItc;
    private BigDecimal excessItc;
    private BigDecimal cashTaxPaid;
    private Double itcUtilizationRatio;
    private Double cashPaymentRatio;
    private Integer filingDelayDays;
    private Double xgbRiskScore;
    private Double dl4jAnomalyScore;
}