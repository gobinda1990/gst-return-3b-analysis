package gov.com.ai.webapp.model.revenue;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OfficeRevenueRowResponse(String retPeriod, LocalDate periodDate, String stJuri, String officeName,
		long filedGstins, BigDecimal taxableValue, BigDecimal outputTax, BigDecimal igst, BigDecimal cgst,
		BigDecimal sgst, BigDecimal cess, BigDecimal eligibleItc, BigDecimal utilizedItc, BigDecimal cashTaxPaid,
		BigDecimal momOutputGrowth, BigDecimal yoyOutputGrowth, BigDecimal momCashGrowth, BigDecimal yoyCashGrowth,
		BigDecimal avgOutputTax3m, BigDecimal avgOutputTax6m, BigDecimal avgOutputTax12m, BigDecimal avgCashTax3m,
		BigDecimal avgCashTax6m, BigDecimal avgCashTax12m, String growthTrend, String growthStatus) {
}
