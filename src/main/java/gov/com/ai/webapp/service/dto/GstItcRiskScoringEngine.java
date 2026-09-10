package gov.com.ai.webapp.service.dto;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class GstItcRiskScoringEngine {

	public BigDecimal calculateRuleScore(BigDecimal potentialExcess, BigDecimal utilizationPercent,
			int discrepancyMonths) {

		int score = 0;

		if (potentialExcess.compareTo(BigDecimal.valueOf(100_000)) >= 0) {
			score += 20;
		}

		if (potentialExcess.compareTo(BigDecimal.valueOf(500_000)) >= 0) {
			score += 20;
		}

		if (potentialExcess.compareTo(BigDecimal.valueOf(1_000_000)) >= 0) {
			score += 20;
		}

		if (utilizationPercent.compareTo(BigDecimal.valueOf(100)) > 0) {
			score += 15;
		}

		if (utilizationPercent.compareTo(BigDecimal.valueOf(120)) > 0) {
			score += 10;
		}

		if (discrepancyMonths >= 3) {
			score += 10;
		}

		if (discrepancyMonths >= 6) {
			score += 10;
		}

		if (discrepancyMonths >= 12) {
			score += 15;
		}

		return BigDecimal.valueOf(Math.min(score, 100));
	}
}
