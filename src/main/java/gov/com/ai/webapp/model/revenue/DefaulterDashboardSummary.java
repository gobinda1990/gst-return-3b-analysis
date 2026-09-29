package gov.com.ai.webapp.model.revenue;

import java.math.BigDecimal;

public record DefaulterDashboardSummary(
        long total,
        long notFiled,
        long filedLate,
        long filedOnTime,
        long notDue,
        long gstr3aEligible,
        long gstr3aIssued,
        long section62Candidates,
        long critical,
        long high,
        long warning,
        long normal,
        BigDecimal totalOutputTax,
        BigDecimal totalTaxableValue) {
}
