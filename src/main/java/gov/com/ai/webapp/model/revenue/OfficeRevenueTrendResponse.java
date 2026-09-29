package gov.com.ai.webapp.model.revenue;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OfficeRevenueTrendResponse(String retPeriod, LocalDate periodDate, BigDecimal outputTax,
		BigDecimal cashTaxPaid, BigDecimal taxableValue) {
}
