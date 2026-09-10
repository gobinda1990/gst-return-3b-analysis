package gov.com.ai.webapp.model.dto;

import java.math.BigDecimal;

public record GstRatioAnalysis(

        BigDecimal nilSupplyRatio,

        BigDecimal itcUtilizationRatio,

        BigDecimal itcToTaxRatio,

        BigDecimal cashPaymentRatio,

        BigDecimal itcPaymentRatio,

        BigDecimal rcmToTaxRatio,

        BigDecimal rcmItcRatio,

        BigDecimal rcmCashRatio
) {
}
