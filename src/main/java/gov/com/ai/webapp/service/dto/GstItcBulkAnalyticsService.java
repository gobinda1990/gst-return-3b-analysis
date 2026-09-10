package gov.com.ai.webapp.service.dto;

import gov.com.ai.webapp.exception.GstAnalyticsException;
import gov.com.ai.webapp.model.dto.GstItcRiskRow;
import gov.com.ai.webapp.repository.dto.GstItcBulkRepository;
import gov.com.ai.webapp.repository.dto.GstItcBulkRepository.GstItcDbRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class GstItcBulkAnalyticsService {

    private final GstItcBulkRepository repository;
    private final GstItcCalculationEngine calculationEngine;
    private final GstItcRiskScoringEngine riskScoringEngine;
    private final GstCombinedRiskEngine combinedRiskEngine;
    
    @Transactional(readOnly = true)
    public List<GstItcRiskRow> analyze(String retPeriod) {

        validatePeriod(retPeriod);

        final List<GstItcDbRow> rows;

        try {

            rows = repository.findByReturnPeriod(retPeriod);

        } catch (DataAccessException ex) {

            log.error(
                    "Database failure during bulk ITC analytics. retPeriod={}, cause={}",
                    retPeriod,
                    getRootCauseMessage(ex),
                    ex
            );

            throw new GstAnalyticsException(
                    "Unable to load ITC analytics data for return period " + retPeriod
                    
            );
        }

        if (rows == null || rows.isEmpty()) {

            log.info(
                    "No ITC records found. retPeriod={}",
                    retPeriod
            );

            return List.of();
        }

        log.info(
                "Starting bulk ITC analytics. retPeriod={}, records={}",
                retPeriod,
                rows.size()
        );

        List<GstItcRiskRow> result =
                new ArrayList<>(rows.size());

        long serial = 1L;

        int successCount = 0;
        int failureCount = 0;

        for (GstItcDbRow row : rows) {

            if (row == null) {
                failureCount++;

                log.warn(
                        "Skipping null ITC database row. retPeriod={}, rowIndex={}",
                        retPeriod,
                        successCount + failureCount
                );

                continue;
            }

            try {

                GstItcRiskRow riskRow =
                        analyzeRow(row, serial);

                result.add(riskRow);

                serial++;
                successCount++;

            } catch (Exception ex) {

                failureCount++;

                /*
                 * Production behaviour:
                 *
                 * One corrupt/invalid GSTIN should not terminate
                 * analytics for the entire return period.
                 */
                log.error(
                        "Failed to analyze individual GSTIN. gstin={}, retPeriod={}, cause={}",
                        row.gstin(),
                        row.retPeriod(),
                        getRootCauseMessage(ex),
                        ex
                );
            }
        }

        log.info(
                "Bulk ITC analytics completed. retPeriod={}, total={}, success={}, failed={}",
                retPeriod,
                rows.size(),
                successCount,
                failureCount
        );

        return result;
    }

    /**
     * Performs analytics for one database row.
     */
    private GstItcRiskRow analyzeRow(
            GstItcDbRow row,
            long serial) {

        GstItcCalculationEngine.Calculation calculation =
                calculationEngine.calculate(row);

        /*
         * Historical discrepancy count is intentionally kept
         * separate from current-period calculation.
         *
         * Replace this with historical repository/service lookup
         * once 6/12 month recurrence analysis is enabled.
         */
        int historicalCount = 0;

        BigDecimal ruleScore =
                safe(
                        riskScoringEngine.calculateRuleScore(
                                safe(calculation.potentialExcessItc()),
                                safe(calculation.utilizationPercent()),
                                historicalCount
                        )
                );

        /*
         * XGB/DL4J scores can be NULL when the ML model has not
         * produced a score for a particular record.
         */
        BigDecimal xgbScore =
                safe(row.xgbRiskScore());

        BigDecimal dl4jScore =
                safe(row.dl4jAnomalyScore());

        GstCombinedRiskEngine.Result risk =
                combinedRiskEngine.calculate(
                        xgbScore,
                        dl4jScore,
                        ruleScore
                );

        /*
         * Actual schema does NOT contain RCM_TOTAL_ITC.
         *
         * Aggregate RCM-related ITC from:
         *
         * ITC_ISRC_IGST
         * ITC_ISRC_CGST
         * ITC_ISRC_SGST
         * ITC_ISRC_CESS
         */
        BigDecimal rcmTotalItc =
                calculateRcmItc(row);

        return new GstItcRiskRow(

                serial,

                row.gstin(),
                row.retPeriod(),

                safe(row.taxableValue()),
                safe(row.totalOutputTax()),

                safe(calculation.eligibleItc()),
                safe(calculation.reversedItc()),
                safe(calculation.ineligibleItc()),
                safe(calculation.utilizedItc()),

                safe(calculation.netEligibleItc()),
                safe(calculation.potentialExcessItc()),

                safe(calculation.utilizationPercent()),
                safe(calculation.itcToTaxPercent()),

                safe(row.rcmTotalTax()),
                rcmTotalItc,

                xgbScore,
                dl4jScore,

                ruleScore,
                safe(risk.finalScore()),

                risk.category(),
                calculation.status(),

                historicalCount,

                buildReason(
                        calculation,
                        historicalCount
                )
        );
    }

    /**
     * Calculates total RCM-related ITC from the actual
     * GST_RET_3B_SUMMARY schema.
     */
    private BigDecimal calculateRcmItc(
            GstItcDbRow row) {

        return safe(row.rcmItcIgst())
                .add(safe(row.rcmItcCgst()))
                .add(safe(row.rcmItcSgst()))
                .add(safe(row.rcmItcCess()));
    }

    /**
     * Builds human-readable scrutiny reasons.
     */
    private String buildReason(
            GstItcCalculationEngine.Calculation calculation,
            int historicalCount) {

        List<String> reasons = new ArrayList<>(4);

        BigDecimal potentialExcess =
                safe(calculation.potentialExcessItc());

        BigDecimal reversed =
                safe(calculation.reversedItc());

        BigDecimal ineligible =
                safe(calculation.ineligibleItc());

        if (potentialExcess.signum() > 0) {

            reasons.add(
                    "Potential ITC utilization exceeding calculated net eligible ITC"
            );
        }

        if (reversed.signum() > 0) {

            reasons.add(
                    "ITC reversal reported"
            );
        }

        if (ineligible.signum() > 0) {

            reasons.add(
                    "Ineligible ITC reported"
            );
        }

        if (historicalCount >= 3) {

            reasons.add(
                    "Repeated ITC discrepancy in historical periods"
            );
        }

        if (reasons.isEmpty()) {

            return "No material ITC discrepancy identified";
        }

        return String.join("; ", reasons);
    }

    /**
     * Validates MMYYYY return period.
     */
    private void validatePeriod(String retPeriod) {

        if (retPeriod == null ||
                !retPeriod.matches("^(0[1-9]|1[0-2])\\d{4}$")) {

            throw new IllegalArgumentException(
                    "Invalid return period: " + retPeriod +
                    ". Expected MMYYYY, e.g. 072026"
            );
        }
    }

    /**
     * Null-safe BigDecimal.
     */
    private static BigDecimal safe(BigDecimal value) {

        return value == null
                ? BigDecimal.ZERO
                : value;
    }

    /**
     * Extracts the deepest database exception message.
     */
    private static String getRootCauseMessage(
            Throwable throwable) {

        Throwable root = throwable;

        while (root.getCause() != null) {
            root = root.getCause();
        }

        return root.getMessage();
    }
}