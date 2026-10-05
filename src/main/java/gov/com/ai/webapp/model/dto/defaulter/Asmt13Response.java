package gov.com.ai.webapp.model.dto.defaulter;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Asmt13Response(
    Long proceedingId,
    String gstin,
    String retPeriod,
    String referenceNo,
    LocalDate orderDate,
    LocalDate first60DayEnd,
    LocalDate extended60DayEnd,
    BigDecimal assessedTotal,
    String status
) {}
