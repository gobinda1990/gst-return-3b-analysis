package gov.com.ai.webapp.service;

import gov.com.ai.webapp.exception.InvalidRequestException;
import gov.com.ai.webapp.model.GstMonthlySummaryDto;
import gov.com.ai.webapp.repository.GstRet3bSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class GstAnalysisService {

    private final GstRet3bSummaryRepository repository;
    private final GSTFinancialYearService gstFinancialYearService;

    // Matches standard FY pattern like "2024-25" or "2025-26"
    private static final Pattern FIN_YEAR_PATTERN = Pattern.compile("^\\d{2}-\\d{2}$");

    /**
     * Fetches monthly GSTR-3B revenue summary by mapping financial year string to period codes.
     *
     * @param finYear Target Financial Year (e.g., "2025-26", "ALL", or empty)
     * @return List of monthly summary DTOs
     */
    @Transactional(readOnly = true)
    public List<GstMonthlySummaryDto> getMonthlyRevenueSummary(String finYear) {
        log.debug("Executing getMonthlyRevenueSummary for finYear: '{}'", finYear);

        try {
            List<String> periods = Collections.emptyList();

            // Handle specific Financial Year filtering
            if (StringUtils.hasText(finYear) && !"ALL".equalsIgnoreCase(finYear.trim())) {
                String sanitizedFy = finYear.trim();
                validateFinancialYearFormat(sanitizedFy);

                log.debug("Resolving period codes for Financial Year: '{}'", sanitizedFy);
                periods = gstFinancialYearService.getReturnPeriodsForFY(sanitizedFy);

                // Short-circuit: Prevent firing an SQL query with an empty IN clause
                if (periods == null || periods.isEmpty()) {
                    log.warn("No return periods resolved for Financial Year: '{}'. Returning empty result.", sanitizedFy);
                    return Collections.emptyList();
                }

                log.debug("Resolved {} periods for FY '{}': {}", periods.size(), sanitizedFy, periods);
            }

            // Fetch summary from DB repository (empty periods list = fetch all)
            log.debug("Querying repository for monthly revenue summary...");
            List<GstMonthlySummaryDto> summaryList = repository.fetchMonthlyRevenueSummary(periods);

            if (summaryList == null || summaryList.isEmpty()) {
                log.info("No GSTR-3B revenue summary records found for finYear: '{}'", finYear);
                return Collections.emptyList();
            }

            log.info("Successfully fetched {} GSTR-3B monthly summary records for finYear: '{}'",
                    summaryList.size(), finYear);

            return summaryList;

        } catch (InvalidRequestException e) {
            log.warn("Validation failed in getMonthlyRevenueSummary: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("An error occurred while fetching GSTR-3B monthly revenue summary for finYear: '{}'", finYear, e);
            throw new RuntimeException("Failed to process GSTR-3B monthly revenue summary request.", e);
        }
    }

    /**
     * Validates format for standard Financial Year string.
     */
    private void validateFinancialYearFormat(String finYear) {
        if (!FIN_YEAR_PATTERN.matcher(finYear).matches()) {
            log.warn("Invalid Financial Year format encountered: '{}'", finYear);
            throw new InvalidRequestException("Invalid Financial Year format. Expected format is 'YYYY-YY' (e.g., '2025-26').");
        }
    }
}