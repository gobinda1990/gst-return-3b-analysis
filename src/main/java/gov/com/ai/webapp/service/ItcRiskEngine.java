package gov.com.ai.webapp.service;

import org.springframework.stereotype.Component;

import gov.com.ai.webapp.config.ItcRiskProperties;

import java.math.BigDecimal;

@Component
public class ItcRiskEngine {

	private final ItcRiskProperties properties;

	public ItcRiskEngine(ItcRiskProperties properties) {
		this.properties = properties;
	}

	public RiskResult calculate(BigDecimal excessItc, BigDecimal utilizationPercent, int historicalOccurrences) {

		BigDecimal amount = ItcCalculationUtil.safe(excessItc);
		BigDecimal utilization = ItcCalculationUtil.safe(utilizationPercent);

		int score = 0;

		if (amount.compareTo(properties.mediumAmount()) >= 0) {
			score += 25;
		}

		if (amount.compareTo(properties.highAmount()) >= 0) {
			score += 25;
		}

		if (amount.compareTo(properties.criticalAmount()) >= 0) {
			score += 25;
		}

		if (utilization.compareTo(properties.highUtilizationPercent()) >= 0) {
			score += 15;
		}

		if (utilization.compareTo(properties.criticalUtilizationPercent()) >= 0) {
			score += 10;
		}

		if (historicalOccurrences >= properties.mediumOccurrences()) {
			score += 10;
		}

		if (historicalOccurrences >= properties.highOccurrences()) {
			score += 10;
		}

		if (historicalOccurrences >= properties.criticalOccurrences()) {
			score += 15;
		}

		score = Math.min(score, 100);

		String category;

		if (score >= 75) {
			category = "CRITICAL";
		} else if (score >= 50) {
			category = "HIGH";
		} else if (score >= 25) {
			category = "MEDIUM";
		} else {
			category = "LOW";
		}

		return new RiskResult(BigDecimal.valueOf(score).setScale(2), category);
	}

	public record RiskResult(BigDecimal score, String category) {
	}
}
