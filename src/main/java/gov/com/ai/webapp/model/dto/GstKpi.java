package gov.com.ai.webapp.model.dto;

import java.math.BigDecimal;

public record GstKpi(

        BigDecimal taxableTurnover,

        BigDecimal outputTax,

        BigDecimal eligibleItc,

        BigDecimal utilizedItc,

        BigDecimal reversedItc,

        BigDecimal ineligibleItc,

        BigDecimal excessItc,

        BigDecimal taxPayable,

        BigDecimal cashTaxPaid,

        BigDecimal itcTaxPaid,

        BigDecimal interestPaid,

        BigDecimal lateFeePaid
) {
}
