package gov.com.ai.webapp.service.dto;

import org.springframework.stereotype.Component;
import gov.com.ai.webapp.repository.dto.GstItcBulkRepository.GstItcDbRow;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class GstItcCalculationEngine {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private static final int DIVISION_SCALE = 8;
    private static final int PERCENT_SCALE = 2;

    /**
     * Calculates current-period ITC analytics.
     *
     * Important:
     *
     * ELIGIBLE_ITC = gross eligible ITC reported
     * REVERSED_ITC = ITC reversal
     * INELIGIBLE_ITC = ineligible ITC
     * UTILIZED_ITC = ITC actually utilized for tax payment
     *
     * Net eligible ITC:
     *
     * ELIGIBLE_ITC
     * - REVERSED_ITC
     * - INELIGIBLE_ITC
     *
     * Potential excess:
     *
     * UTILIZED_ITC - NET_ELIGIBLE_ITC
     */
    public Calculation calculate(GstItcDbRow row) {

        if (row == null) {
            throw new IllegalArgumentException(
                    "GST ITC database row cannot be null"
            );
        }

        BigDecimal eligible =
                nonNegative(row.eligibleItc());

        BigDecimal utilized =
                nonNegative(row.utilizedItc());

        BigDecimal reversed =
                nonNegative(row.reversedItc());

        BigDecimal ineligible =
                nonNegative(row.ineligibleItc());

        BigDecimal totalOutputTax =
                nonNegative(row.totalOutputTax());

        /*
         * ---------------------------------------------------------
         * 1. NET ELIGIBLE ITC
         * ---------------------------------------------------------
         *
         * Gross eligible ITC
         *       - reversal
         *       - ineligible
         *
         * Never allow negative net eligible ITC.
         */
        BigDecimal netEligible =
                eligible
                        .subtract(reversed)
                        .subtract(ineligible)
                        .max(ZERO);

        /*
         * ---------------------------------------------------------
         * 2. POTENTIAL EXCESS ITC
         * ---------------------------------------------------------
         *
         * Utilized ITC above calculated net eligible ITC.
         */
        BigDecimal potentialExcess =
                utilized
                        .subtract(netEligible)
                        .max(ZERO);

        /*
         * ---------------------------------------------------------
         * 3. ITC UTILIZATION %
         * ---------------------------------------------------------
         *
         * Utilized ITC / Net eligible ITC * 100
         */
        BigDecimal utilizationPercent =
                percentage(
                        utilized,
                        netEligible
                );

        /*
         * ---------------------------------------------------------
         * 4. ITC TO OUTPUT TAX %
         * ---------------------------------------------------------
         *
         * Utilized ITC / Output tax * 100
         */
        BigDecimal itcToTaxPercent =
                percentage(
                        utilized,
                        totalOutputTax
                );

        /*
         * ---------------------------------------------------------
         * 5. RCM ITC
         * ---------------------------------------------------------
         *
         * There is NO RCM_TOTAL_ITC column in the actual table.
         *
         * RCM-related ITC is stored in:
         *
         * ITC_ISRC_IGST
         * ITC_ISRC_CGST
         * ITC_ISRC_SGST
         * ITC_ISRC_CESS
         */
        BigDecimal rcmItc =
                calculateRcmItc(row);

        BigDecimal rcmTax =
                nonNegative(row.rcmTotalTax());

        /*
         * RCM ITC / RCM Tax * 100
         */
        BigDecimal rcmPercent =
                percentage(
                        rcmItc,
                        rcmTax
                );

        /*
         * ---------------------------------------------------------
         * 6. DISCREPANCY STATUS
         * ---------------------------------------------------------
         */
        String status =
                determineStatus(
                        potentialExcess,
                        reversed,
                        ineligible
                );

        return new Calculation(

                eligible,
                utilized,
                reversed,
                ineligible,

                netEligible,
                potentialExcess,

                utilizationPercent,
                itcToTaxPercent,

                rcmItc,
                rcmPercent,

                status
        );
    }

    /**
     * Calculates aggregate RCM-related ITC from the actual
     * GST_RET_3B_SUMMARY columns.
     */
    private BigDecimal calculateRcmItc(
            GstItcDbRow row) {

        return nonNegative(row.rcmItcIgst())
                .add(nonNegative(row.rcmItcCgst()))
                .add(nonNegative(row.rcmItcSgst()))
                .add(nonNegative(row.rcmItcCess()));
    }

    /**
     * Determines current-period ITC discrepancy status.
     */
    private String determineStatus(
            BigDecimal potentialExcess,
            BigDecimal reversed,
            BigDecimal ineligible) {

        if (potentialExcess.signum() > 0) {
            return "POTENTIAL_EXCESS_ITC";
        }

        if (ineligible.signum() > 0) {
            return "INELIGIBLE_ITC_REPORTED";
        }

        if (reversed.signum() > 0) {
            return "ITC_REVERSAL_REPORTED";
        }

        return "NO_DISCREPANCY";
    }

    /**
     * Calculates percentage safely.
     *
     * Result is always:
     *
     * 0.00 <= result
     *
     * and is returned with 2 decimal places.
     */
    private BigDecimal percentage(
            BigDecimal numerator,
            BigDecimal denominator) {

        BigDecimal safeNumerator =
                nonNegative(numerator);

        BigDecimal safeDenominator =
                nonNegative(denominator);

        if (safeDenominator.signum() <= 0) {
            return ZERO.setScale(
                    PERCENT_SCALE,
                    RoundingMode.HALF_UP
            );
        }

        return safeNumerator
                .divide(
                        safeDenominator,
                        DIVISION_SCALE,
                        RoundingMode.HALF_UP
                )
                .multiply(ONE_HUNDRED)
                .setScale(
                        PERCENT_SCALE,
                        RoundingMode.HALF_UP
                );
    }

    /**
     * Converts null/negative monetary values to zero.
     *
     * Negative ITC values should not be allowed to distort
     * discrepancy calculations.
     */
    private BigDecimal nonNegative(
            BigDecimal value) {

        if (value == null) {
            return ZERO;
        }

        return value.signum() < 0
                ? ZERO
                : value;
    }

    /**
     * Immutable calculation result.
     */
    public record Calculation(

            BigDecimal eligibleItc,

            BigDecimal utilizedItc,

            BigDecimal reversedItc,

            BigDecimal ineligibleItc,

            BigDecimal netEligibleItc,

            BigDecimal potentialExcessItc,

            BigDecimal utilizationPercent,

            BigDecimal itcToTaxPercent,

            BigDecimal rcmItc,

            BigDecimal rcmItcPercent,

            String status

    ) {
    }
}