package gov.com.ai.webapp.model.dto;

import java.math.BigDecimal;
public record GstItcBreakup(

        BigDecimal importGoodsIgst,
        BigDecimal importGoodsCgst,
        BigDecimal importGoodsSgst,
        BigDecimal importGoodsCess,

        BigDecimal isrcIgst,
        BigDecimal isrcCgst,
        BigDecimal isrcSgst,
        BigDecimal isrcCess,

        BigDecimal otherIgst,
        BigDecimal otherCgst,
        BigDecimal otherSgst,
        BigDecimal otherCess,

        BigDecimal isdIgst,
        BigDecimal isdCgst,
        BigDecimal isdSgst,
        BigDecimal isdCess,

        BigDecimal netIgst,
        BigDecimal netCgst,
        BigDecimal netSgst,
        BigDecimal netCess,
        BigDecimal netTotal
) {
}
