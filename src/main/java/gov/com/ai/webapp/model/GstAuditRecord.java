package gov.com.ai.webapp.model;

import java.math.BigDecimal;

public record GstAuditRecord(
    String gstin,
    String retPeriod,
    BigDecimal taxableValue,
    BigDecimal totalOutputTax,   
    BigDecimal cashTaxPaid,
    BigDecimal utilizedItc,
    BigDecimal eligibleItc,
    BigDecimal excessItc,
    Integer filingDelayDays,
    BigDecimal riskScorePct,
    BigDecimal itcUtilizationPct,
    String officerRiskCategory,
    String statutoryAction,
    String asmt10Reason
) {}