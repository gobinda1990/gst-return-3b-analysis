package gov.com.ai.webapp.model.dto;

import java.math.BigDecimal;

public record GstTaxBreakup(

        BigDecimal igst,

        BigDecimal cgst,

        BigDecimal sgst,

        BigDecimal cess,

        BigDecimal reverseChargeTax,

        BigDecimal zeroRatedValue,

        BigDecimal nilExemptValue,

        BigDecimal nonGstValue,

        BigDecimal rcmTaxableValue
) {
}
