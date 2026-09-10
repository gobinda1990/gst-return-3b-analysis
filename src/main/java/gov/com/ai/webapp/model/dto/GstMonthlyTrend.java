package gov.com.ai.webapp.model.dto;

import java.math.BigDecimal;

public record GstMonthlyTrend(

        String returnPeriod,

        BigDecimal taxableTurnover,

        BigDecimal outputTax,

        BigDecimal eligibleItc,

        BigDecimal utilizedItc,

        BigDecimal taxPayable,

        BigDecimal cashTaxPaid,

        BigDecimal filingDelayDays,

        BigDecimal itcUtilizationRatio,

        BigDecimal itcToTaxRatio
) {
}
