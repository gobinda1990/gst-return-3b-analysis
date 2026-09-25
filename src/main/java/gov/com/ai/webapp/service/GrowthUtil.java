package gov.com.ai.webapp.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class GrowthUtil {

	private GrowthUtil() {
	}

	public static BigDecimal growth(BigDecimal current, BigDecimal previous) {

		if (current == null || previous == null) {

			return null;
		}

		if (previous.signum() == 0) {
			return null;
		}

		return current.subtract(previous).divide(previous.abs(), 8, RoundingMode.HALF_UP)
				.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
	}

	public static float growthFloat(BigDecimal current, BigDecimal previous) {

		BigDecimal result = growth(current, previous);

		return result == null ? Float.NaN : result.floatValue();
	}

	public static BigDecimal safe(BigDecimal value) {

		return value == null ? BigDecimal.ZERO : value;
	}
}
