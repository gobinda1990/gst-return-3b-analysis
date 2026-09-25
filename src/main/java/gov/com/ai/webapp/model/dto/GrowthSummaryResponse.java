package gov.com.ai.webapp.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GrowthSummaryResponse {

	private long totalGstins;

	private BigDecimal totalTaxableValue;
	private BigDecimal totalOutputTax;
	private BigDecimal totalEligibleItc;
	private BigDecimal totalCashTaxPaid;

	private BigDecimal avgMomTaxableGrowth;
	private BigDecimal avgMomOutputTaxGrowth;

	private BigDecimal avgYoyTaxableGrowth;
	private BigDecimal avgYoyOutputTaxGrowth;

	private long strongGrowthCount;
	private long growthCount;
	private long stableCount;
	private long declineCount;
	private long strongDeclineCount;

	private long highRiskCount;
	private long mediumRiskCount;
	private long lowRiskCount;
}