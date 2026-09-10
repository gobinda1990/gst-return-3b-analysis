package gov.com.ai.webapp.model;

import java.math.BigDecimal;

public record ItcSummaryRow(

		String gstin, String retPeriod,

		BigDecimal eligibleItc, BigDecimal utilizedItc, BigDecimal reversedItc, BigDecimal ineligibleItc,
		BigDecimal excessItc,

		BigDecimal totalOutputTax,

		BigDecimal rcmTotalTax, BigDecimal rcmTotalItc,

		BigDecimal storedItcUtilizationRatio, BigDecimal storedItcToTaxRatio

) {
}
