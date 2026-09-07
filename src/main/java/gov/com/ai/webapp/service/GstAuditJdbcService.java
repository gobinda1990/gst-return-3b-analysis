package gov.com.ai.webapp.service;

import gov.com.ai.webapp.model.GstAuditRecord;
import gov.com.ai.webapp.model.PagedAuditResponse;
import gov.com.ai.webapp.repository.GstAuditJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GstAuditJdbcService {

    private final GstAuditJdbcRepository repository;

    /**
     * Retrieves paged GST audit records.
     * Uses cached dataset for ultra-fast, zero-DB-overhead pagination.
     *
     * @param retPeriod Tax return period (e.g., "032026")
     * @param page Zero-based page index
     * @param size Page size
     */
    @Transactional(readOnly = true)
    public PagedAuditResponse<GstAuditRecord> getAuditPipeline(String retPeriod, int page, int size) {
        List<GstAuditRecord> allRecords = getAllAuditRecordsCached(retPeriod);

        long totalRecords = allRecords.size();
        int totalPages = (int) Math.ceil((double) totalRecords / size);

        int fromIndex = Math.min(page * size, (int) totalRecords);
        int toIndex = Math.min(fromIndex + size, (int) totalRecords);

        List<GstAuditRecord> pagedContent = (fromIndex >= totalRecords)
                ? Collections.emptyList()
                : allRecords.subList(fromIndex, toIndex);

        return new PagedAuditResponse<>(pagedContent, page, size, totalRecords, totalPages);
    }

    /**
     * Single cache entry point for the entire return period's audit pipeline.
     */
    @Cacheable(value = "auditPipelineByPeriod", key = "#retPeriod", unless = "#result == null || #result.isEmpty()")
    public List<GstAuditRecord> getAllAuditRecordsCached(String retPeriod) {
        return repository.fetchScrutinyPipeline(retPeriod);
    }
}