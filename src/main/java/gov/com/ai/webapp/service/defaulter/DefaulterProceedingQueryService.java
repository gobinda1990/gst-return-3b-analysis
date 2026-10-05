package gov.com.ai.webapp.service.defaulter;

import gov.com.ai.webapp.exception.DefaulterNotFoundException;
import gov.com.ai.webapp.exception.InvalidProceedingQueryException;
import gov.com.ai.webapp.model.dto.defaulter.ProceedingAuditResponse;
import gov.com.ai.webapp.model.dto.defaulter.ProceedingPageResponse;
import gov.com.ai.webapp.model.dto.defaulter.ProceedingRowResponse;
import gov.com.ai.webapp.model.dto.defaulter.ProceedingSummaryResponse;
import gov.com.ai.webapp.repository.DefaulterProceedingQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class DefaulterProceedingQueryService {

    private static final Pattern PERIOD =
            Pattern.compile("^(0[1-9]|1[0-2])\\d{4}$");

    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "NOT_DUE",
            "FILED_ON_TIME",
            "FILED_LATE",
            "RETURN_PENDING",
            "GSTR3A_ELIGIBLE",
            "GSTR3A_COMPLIANCE_PENDING",
            "COMPLIED_AFTER_GSTR3A",
            "SECTION62_ELIGIBLE",
            "ASMT13_ISSUED",
            "ASMT13_DEEMED_WITHDRAWN",
            "ASMT13_FINAL"
    );

    private final DefaulterProceedingQueryRepository repository;

    @Transactional(readOnly = true)
    public ProceedingSummaryResponse summary(
            String retPeriod,
            String status,
            String search) {

        QueryFilter filter = normalize(retPeriod, status, search);

        return repository.summary(
                filter.retPeriod(),
                filter.status(),
                filter.search()
        );
    }

    @Transactional(readOnly = true)
    public ProceedingPageResponse<ProceedingRowResponse> find(
            String retPeriod,
            String status,
            String search,
            int page,
            int size) {

        if (page < 0) {
            throw new InvalidProceedingQueryException(
                    "page must be greater than or equal to 0"
            );
        }

        if (size < 1 || size > 100) {
            throw new InvalidProceedingQueryException(
                    "size must be between 1 and 100"
            );
        }

        QueryFilter filter = normalize(retPeriod, status, search);

        long total = repository.count(
                filter.retPeriod(),
                filter.status(),
                filter.search()
        );

        List<ProceedingRowResponse> rows =
                total == 0
                        ? List.of()
                        : repository.findPage(
                                filter.retPeriod(),
                                filter.status(),
                                filter.search(),
                                page,
                                size
                        );

        int totalPages = total == 0
                ? 0
                : (int) ((total + size - 1L) / size);

        return new ProceedingPageResponse<>(
                rows,
                total,
                totalPages,
                page,
                size
        );
    }

    @Transactional(readOnly = true)
    public ProceedingRowResponse findById(Long proceedingId) {

        validateId(proceedingId);

        return repository.findById(proceedingId)
                .orElseThrow(() ->
                        new DefaulterNotFoundException(
                                "Proceeding not found: " + proceedingId
                        )
                );
    }

    @Transactional(readOnly = true)
    public List<ProceedingAuditResponse> findAudit(Long proceedingId) {

        validateId(proceedingId);

        if (!repository.existsById(proceedingId)) {
            throw new DefaulterNotFoundException(
                    "Proceeding not found: " + proceedingId
            );
        }

        return repository.findAudit(proceedingId);
    }

    private QueryFilter normalize(
            String retPeriod,
            String status,
            String search) {

        String normalizedPeriod = trimToNull(retPeriod);
        String normalizedStatus = trimToNull(status);
        String normalizedSearch = trimToNull(search);

        if (normalizedPeriod != null &&
                !PERIOD.matcher(normalizedPeriod).matches()) {
            throw new InvalidProceedingQueryException(
                    "retPeriod must be in MMYYYY format"
            );
        }

        if (normalizedStatus != null) {
            normalizedStatus =
                    normalizedStatus.toUpperCase(Locale.ROOT);

            if (!ALLOWED_STATUSES.contains(normalizedStatus)) {
                throw new InvalidProceedingQueryException(
                        "Unsupported proceeding status: " + normalizedStatus
                );
            }
        }

        if (normalizedSearch != null &&
                normalizedSearch.length() > 100) {
            throw new InvalidProceedingQueryException(
                    "search must not exceed 100 characters"
            );
        }

        return new QueryFilter(
                normalizedPeriod,
                normalizedStatus,
                normalizedSearch
        );
    }

    private void validateId(Long id) {
        if (id == null || id < 1) {
            throw new InvalidProceedingQueryException(
                    "proceedingId must be greater than 0"
            );
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record QueryFilter(
            String retPeriod,
            String status,
            String search) {
    }
}
