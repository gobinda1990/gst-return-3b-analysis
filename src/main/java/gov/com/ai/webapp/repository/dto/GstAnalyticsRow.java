package gov.com.ai.webapp.repository.dto;

import java.math.BigDecimal;

public record GstAnalyticsRow(

		String gstin, String stateCode,

		int totalReturns,

		java.sql.Date firstFilingDate, java.sql.Date latestFilingDate,

		BigDecimal taxableTurnover, BigDecimal outputTax,

		BigDecimal eligibleItc, BigDecimal utilizedItc, BigDecimal reversedItc, BigDecimal ineligibleItc,
		BigDecimal excessItc,

		BigDecimal cashTaxPaid, BigDecimal itcPaymentTotal,

		BigDecimal interestPaid, BigDecimal lateFeePaid,

		BigDecimal outputIgst, BigDecimal outputCgst, BigDecimal outputSgst, BigDecimal outputCess,

		BigDecimal rcmTotalTax, BigDecimal zeroRatedValue, BigDecimal nilExemptValue, BigDecimal nonGstValue,
		BigDecimal rcmTaxableValue,

		BigDecimal averageDelay, int maxDelay,

		int delayedReturns, int ontimeReturns,

		BigDecimal xgbRiskScore, BigDecimal anomalyScore) {
}
