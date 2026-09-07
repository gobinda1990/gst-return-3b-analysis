package gov.com.ai.webapp.model;

import java.math.BigDecimal;

public record GstMonthlySummaryDto(String retPeriod, Long totalTaxpayersFiled, BigDecimal grossTaxableValue,
		BigDecimal totalIgstCollected, BigDecimal totalCgstCollected, BigDecimal totalSgstCollected,
		BigDecimal totalCessCollected, BigDecimal totalGrossRevenue, BigDecimal totalCashCollection,
		BigDecimal totalCreditUtilized, BigDecimal cashRealizationPct, BigDecimal itcUtilizationPct) {
}
