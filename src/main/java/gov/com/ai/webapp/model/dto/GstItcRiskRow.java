package gov.com.ai.webapp.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record GstItcRiskRow(

        long serialNo,

        String gstin,

        String retPeriod,

        BigDecimal taxableValue,

        BigDecimal totalOutputTax,

        BigDecimal eligibleItc,

        BigDecimal reversedItc,

        BigDecimal ineligibleItc,

        BigDecimal utilizedItc,

        BigDecimal netEligibleItc,

        @JsonProperty("excessItc")
        BigDecimal potentialExcessItc,

        @JsonProperty("itcUtilizationPct")
        BigDecimal itcUtilizationPercent,

        @JsonProperty("itcToTaxPct")
        BigDecimal itcToTaxPercent,

        BigDecimal rcmTotalTax,

        /*
         * Calculated from ITC_ISRC_*.
         * This is NOT an Oracle column.
         */
        BigDecimal rcmTotalItc,

        BigDecimal xgbRiskScore,

        BigDecimal dl4jAnomalyScore,

        @JsonProperty("ruleRiskScore")
        BigDecimal ruleRiskScore,

        @JsonProperty("riskScorePct")
        BigDecimal finalRiskScore,

        String riskCategory,

        String discrepancyStatus,

        int discrepancyMonths,

        @JsonProperty("asmt10Reason")
        String scrutinyReason
) {
}