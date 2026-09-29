package gov.com.ai.webapp.model.revenue;

public record RevenueDashboardFilter(String retPeriod, String office, String growthStatus, String search, int page,
		int size) {
}
