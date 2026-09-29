package gov.com.ai.webapp.model.revenue;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DefaulterDashboardRow(
        String gstin,
        String tradeName,
        String retPeriod,
        LocalDate dueDate,
        String filingStatus,
        LocalDate filingDate,
        int delayDays,
        BigDecimal taxableValue,
        BigDecimal outputTax,
        String growthStatus,
        String riskLevel,
        BigDecimal riskScore,
        BigDecimal defaultScore,
        String defaultLevel,
        int historicalDefaultCount,
        String gstr3aEligible,
        String gstr3aStatus,
        LocalDate gstr3aNoticeDate,
        String gstr3aReferenceNo,
        LocalDate responseDueDate,
        String returnFiledAfterNotice,
        String section62Candidate,
        String officerReviewStatus,
        String stJuri,
        String officeName) {
}
