package gov.com.ai.webapp.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class ItcCalculationUtil {

    private ItcCalculationUtil() {
    }

    public static final BigDecimal ZERO = BigDecimal.ZERO;

    public static BigDecimal safe(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    public static BigDecimal nonNegative(BigDecimal value) {
        return safe(value).max(ZERO);
    }

    public static BigDecimal netEligibleItc(
            BigDecimal eligible,
            BigDecimal reversed,
            BigDecimal ineligible) {

        return nonNegative(
                safe(eligible)
                        .subtract(safe(reversed))
                        .subtract(safe(ineligible))
        );
    }

    public static BigDecimal potentialExcessItc(
            BigDecimal utilized,
            BigDecimal netEligible) {

        return nonNegative(
                safe(utilized).subtract(safe(netEligible))
        );
    }

    public static BigDecimal percentage(
            BigDecimal numerator,
            BigDecimal denominator) {

        if (denominator == null
                || denominator.compareTo(ZERO) <= 0) {
            return ZERO;
        }

        return numerator
                .divide(denominator, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal ratioForOracle(
            BigDecimal numerator,
            BigDecimal denominator) {

        if (denominator == null
                || denominator.compareTo(ZERO) <= 0) {
            return ZERO.setScale(4);
        }

        BigDecimal ratio = numerator
                .divide(denominator, 8, RoundingMode.HALF_UP);

        /*
         * Oracle NUMBER(5,4)
         * maximum safe value = 9.9999
         */
        return ratio
                .min(new BigDecimal("9.9999"))
                .setScale(4, RoundingMode.HALF_UP);
    }
}
