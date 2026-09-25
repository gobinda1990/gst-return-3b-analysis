package gov.com.ai.webapp.service;

import gov.com.ai.webapp.model.Return3BSummaryBean;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class GrowthStatistics {

	private GrowthStatistics() {
	}

	public static float volatility(Map<YearMonth, Return3BSummaryBean> history, YearMonth currentPeriod) {

		List<Double> growth = new ArrayList<>();

		/*
		 * Last six available MONTH-TO-MONTH growth observations.
		 *
		 * We only use a growth value when both months actually exist.
		 */
		for (int i = 6; i >= 1; i--) {

			YearMonth p = currentPeriod.minusMonths(i);

			YearMonth previous = p.minusMonths(1);

			Return3BSummaryBean current = history.get(p);

			Return3BSummaryBean previousBean = history.get(previous);

			if (current == null || previousBean == null) {
				continue;
			}

			BigDecimal g = GrowthUtil.growth(current.getTaxableValue(), previousBean.getTaxableValue());

			if (g != null) {
				growth.add(g.doubleValue());
			}
		}

		if (growth.size() < 2) {
			return 0f;
		}

		double mean = growth.stream().mapToDouble(Double::doubleValue).average().orElse(0);

		double variance = growth.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0);

		return (float) Math.sqrt(variance);
	}

	public static float consistency(float volatility) {

		if (!Float.isFinite(volatility)) {
			return 0f;
		}

		/*
		 * 100 = highly consistent 0 = highly volatile
		 */
		return Math.max(0f, 100f - Math.min(volatility, 100f));
	}
}
