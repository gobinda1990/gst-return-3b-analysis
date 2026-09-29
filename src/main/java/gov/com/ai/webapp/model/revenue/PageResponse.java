package gov.com.ai.webapp.model.revenue;

import java.util.List;

public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public static <T> PageResponse<T> of(List<T> content, int page, int size, long total) {
        int pages = total == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new PageResponse<>(
                content, page, size, total, pages,
                page == 0,
                pages == 0 || page >= pages - 1);
    }
}
