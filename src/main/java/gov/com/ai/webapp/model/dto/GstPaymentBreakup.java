package gov.com.ai.webapp.model.dto;

import java.math.BigDecimal;

public record GstPaymentBreakup(

        BigDecimal rcmPayment,

        BigDecimal cashIgst,
        BigDecimal cashCgst,
        BigDecimal cashSgst,
        BigDecimal cashCess,
        BigDecimal cashTaxPaid,

        BigDecimal itcIgst,
        BigDecimal itcCgst,
        BigDecimal itcSgst,
        BigDecimal itcCess,
        BigDecimal itcPaymentTotal
) {
}
