package gov.com.ai.webapp.service.dto;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class GstCombinedRiskEngine {

	public Result calculate(BigDecimal ruleScore, BigDecimal xgbScore, BigDecimal dl4jScore) {

		BigDecimal rule = normalizePercent(ruleScore);

		BigDecimal xgb = normalizePercent(xgbScore);

		BigDecimal dl4j = normalizePercent(dl4jScore);

		/*
		 * Configurable production weighting.
		 */
		BigDecimal finalScore = rule.multiply(BigDecimal.valueOf(0.40)).add(xgb.multiply(BigDecimal.valueOf(0.40)))
				.add(dl4j.multiply(BigDecimal.valueOf(0.20))).setScale(2, RoundingMode.HALF_UP);

		String category;

		if (finalScore.compareTo(BigDecimal.valueOf(75)) >= 0) {

			category = "CRITICAL";

		} else if (finalScore.compareTo(BigDecimal.valueOf(50)) >= 0) {

			category = "HIGH";

		} else if (finalScore.compareTo(BigDecimal.valueOf(25)) >= 0) {

			category = "MEDIUM";

		} else {

			category = "LOW";
		}

		return new Result(finalScore, category);
	}

	private BigDecimal normalizePercent(BigDecimal value) {

		if (value == null) {
			return BigDecimal.ZERO;
		}

		BigDecimal result = value;

		/*
		 * Stored ML scores are expected to be 0..1.
		 */
		if (result.compareTo(BigDecimal.ONE) <= 0) {
			result = result.multiply(BigDecimal.valueOf(100));
		}

		return result.max(BigDecimal.ZERO).min(BigDecimal.valueOf(100));
	}

	public record Result(BigDecimal finalScore, String category) {
	}
}
