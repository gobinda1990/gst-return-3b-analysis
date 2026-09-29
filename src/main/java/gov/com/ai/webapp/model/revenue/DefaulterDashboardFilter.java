package gov.com.ai.webapp.model.revenue;

public record DefaulterDashboardFilter(
        String retPeriod,
        String office,
        String filingStatus,
        String riskLevel,
        String defaultLevel,
        String gstr3aEligible,
        String search,
        int page,
        int size) {
}
