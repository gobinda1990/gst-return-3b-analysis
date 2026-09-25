package gov.com.ai.webapp.model;

import java.math.BigDecimal;

/**
 * Monthly (or period-wise) GSTR-3B revenue summary for the office finance
 * dashboard, financial-year filterable.
 *
 * Field notes:
 * - totalNormalOutputTax excludes RCM by design (matches the underlying
 *   total_output_tax column, which is normal outward liability only).
 * - totalRcmTax / totalTaxLiability are new - see
 *   GstRet3bSummaryRepository for why "total_output_tax" alone
 *   understates what the office is actually owed for the period.
 * - totalCashCollection now comes from the real cash_tax_paid column
 *   (includes RCM cash), not a derived subtraction - see the repository
 *   fix notes for why the old derivation was wrong.
 */
public record GstMonthlySummaryDto(String retPeriod, Long totalTaxpayersFiled, BigDecimal grossTaxableValue,
		BigDecimal totalIgstCollected, BigDecimal totalCgstCollected, BigDecimal totalSgstCollected,
		BigDecimal totalCessCollected, BigDecimal totalNormalOutputTax, BigDecimal totalRcmTax,
		BigDecimal totalTaxLiability, BigDecimal totalCashCollection, BigDecimal totalCreditUtilized,
		BigDecimal totalLateFeeCollected, BigDecimal totalInterestCollected, BigDecimal cashRealizationPct,
		BigDecimal itcUtilizationPct) {
}
