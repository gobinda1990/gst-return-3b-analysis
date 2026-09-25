package gov.com.ai.webapp.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {

    @Builder.Default
    private List<T> content = Collections.emptyList();

    private long totalElements;
    private int totalPages;

    private int page;
    private int size;

    private boolean first;
    private boolean last;
}