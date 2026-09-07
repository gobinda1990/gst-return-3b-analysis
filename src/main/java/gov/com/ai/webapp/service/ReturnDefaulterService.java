package gov.com.ai.webapp.service;

import gov.com.ai.webapp.model.GstDefaulterRecord;
import gov.com.ai.webapp.model.PagedAuditResponse;
import gov.com.ai.webapp.repository.ReturnDefaulterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReturnDefaulterService {

    private final ReturnDefaulterRepository repository;

    /**
     * Fetches all defaulter records for a period from cache/DB and returns a paginated slice.
     *
     * @param retPeriod Return period key (e.g., "032026")
     * @param page Zero-based page index
     * @param size Number of records per page
     */
    @Transactional(readOnly = true)
    public PagedAuditResponse<GstDefaulterRecord> getDefaulterPipeline(String retPeriod, int page, int size) {
        // Fetch full cached list (Database is hit ONLY ONCE per retPeriod)
        List<GstDefaulterRecord> allDefaulters = getAllDefaultersCached(retPeriod);

        int totalElements = allDefaulters.size();
        int fromIndex = Math.min(page * size, totalElements);
        int toIndex = Math.min(fromIndex + size, totalElements);

        List<GstDefaulterRecord> pageContent = (fromIndex > totalElements)
                ? Collections.emptyList()
                : allDefaulters.subList(fromIndex, toIndex);

        return PagedAuditResponse.of(pageContent, page, size, totalElements);
    }

    /**
     * Caches the entire result set per 'retPeriod'.
     * Subsequent requests with the same retPeriod hit the cache directly.
     */
    @Cacheable(value = "defaultersByPeriod", key = "#retPeriod", unless = "#result == null || #result.isEmpty()")
    public List<GstDefaulterRecord> getAllDefaultersCached(String retPeriod) {
        return repository.fetchDefaulters(retPeriod);
    }
}