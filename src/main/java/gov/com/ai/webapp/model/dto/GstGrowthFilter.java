package gov.com.ai.webapp.model.dto;

public record GstGrowthFilter(
        String period,
        String search,
        String trend,
        int page,
        int size) {
}
