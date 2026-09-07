package gov.com.ai.webapp.model;

import java.util.List;

/**
 * Generic pagination wrapper DTO using Java 17 Records.
 * 
 * @param <T>           The item type contained in the page content list.
 * @param content       The current page's dataset.
 * @param pageNumber    Current zero-based page index (0, 1, 2...).
 * @param pageSize      Number of items requested per page.
 * @param totalElements Total count of records across all pages in the database.
 * @param totalPages    Total calculated number of pages available.
 */
public record PagedAuditResponse<T>(List<T> content, int pageNumber, int pageSize, long totalElements, int totalPages) {
	/**
	 * Compact Static Factory Method for easy instantiation from raw values.
	 */
	public static <T> PagedAuditResponse<T> of(List<T> content, int pageNumber, int pageSize, long totalElements) {
		int totalPages = pageSize > 0 ? (int) Math.ceil((double) totalElements / pageSize) : 0;
		return new PagedAuditResponse<>(content, pageNumber, pageSize, totalElements, totalPages);
	}
}
