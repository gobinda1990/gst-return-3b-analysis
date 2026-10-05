package gov.com.ai.webapp.model.dto.defaulter;

import java.util.List;

public record ProceedingPageResponse<T>(
        List<T> content,
        long totalElements,
        int totalPages,
        int page,
        int size
) {
    public ProceedingPageResponse {
        content = content == null ? List.of() : List.copyOf(content);
    }
}
