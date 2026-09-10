package gov.com.ai.webapp.model;

import java.math.BigDecimal;
import java.util.List;

public record ItcDiscrepancyResponse(String gstin, String retPeriod,

		BigDecimal eligibleItc, BigDecimal reversedItc, BigDecimal ineligibleItc, BigDecimal utilizedItc,

		BigDecimal netEligibleItc, BigDecimal potentialExcessItc,

		BigDecimal itcUtilizationPercent, BigDecimal itcToTaxPercent,

		BigDecimal rcmTotalTax, BigDecimal rcmTotalItc,

		BigDecimal rcmItcPercent,

		String discrepancyStatus, String riskCategory, BigDecimal riskScore,

		Integer historyMonths, Integer historicalDiscrepancyCount,

		List<String> scrutinyReasons) {
}
