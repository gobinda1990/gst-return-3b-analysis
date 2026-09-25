package gov.com.ai.webapp.service;

import gov.com.ai.webapp.model.Return3BSummaryBean;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.Function;

public final class Gstr3bFeatureBuilder {

	private Gstr3bFeatureBuilder() {
	}

	public static float[] build(Return3BSummaryBean current, Return3BSummaryBean previous1,
			Return3BSummaryBean previous3, Return3BSummaryBean previous6, Return3BSummaryBean previous12,
			float growthVolatility, float growthConsistency) {

		if (current == null) {
			throw new IllegalArgumentException("Current return cannot be null");
		}

		float[] f = new float[Gstr3bFeatureDefinition.FEATURE_COUNT];

		BigDecimal taxable = safe(current.getTaxableValue());

		BigDecimal outputTax = safe(current.getTotalOutputTax());

		BigDecimal eligibleItc = safe(current.getEligibleItc());

		BigDecimal utilizedItc = safe(current.getUtilizedItc());

		BigDecimal cashTax = safe(current.getCashTaxPaid());

		BigDecimal rcmTax = safe(current.getRcmTotalTax());

		BigDecimal liability = outputTax.add(rcmTax);

		// 01
		f[0] = taxable.floatValue();

		// 02
		f[1] = outputTax.floatValue();

		// 03
		f[2] = eligibleItc.floatValue();

		// 04
		f[3] = utilizedItc.floatValue();

		// 05
		f[4] = cashTax.floatValue();

		// 06
		f[5] = ratio(utilizedItc, eligibleItc);

		// 07
		f[6] = ratio(eligibleItc, liability);

		// 08
		f[7] = ratio(cashTax, liability);

		// 09
		f[8] = ratio(rcmTax, liability);

		// 10
		f[9] = ratio(rcmTax, eligibleItc);

		// 11
		f[10] = ratio(rcmTax, cashTax);

		// 12
		f[11] = current.getFilingDelayDays() == null ? 0f : current.getFilingDelayDays().floatValue();

		// 13
		f[12] = growth(taxable, get(previous1, Return3BSummaryBean::getTaxableValue));

		// 14
		f[13] = growth(taxable, get(previous3, Return3BSummaryBean::getTaxableValue));

		// 15
		f[14] = growth(taxable, get(previous6, Return3BSummaryBean::getTaxableValue));

		// 16
		f[15] = growth(taxable, get(previous12, Return3BSummaryBean::getTaxableValue));

		// 17
		f[16] = growth(outputTax, get(previous3, Return3BSummaryBean::getTotalOutputTax));

		// 18
		f[17] = growth(outputTax, get(previous6, Return3BSummaryBean::getTotalOutputTax));

		// 19
		f[18] = growth(eligibleItc, get(previous3, Return3BSummaryBean::getEligibleItc));

		// 20
		f[19] = growth(eligibleItc, get(previous6, Return3BSummaryBean::getEligibleItc));

		// 21
		f[20] = growth(cashTax, get(previous3, Return3BSummaryBean::getCashTaxPaid));

		// 22
		f[21] = growth(cashTax, get(previous6, Return3BSummaryBean::getCashTaxPaid));

		// 23
		f[22] = growth(liability, liability(previous3));

		// 24
		f[23] = growth(liability, liability(previous6));

		// 25
		f[24] = growthVolatility;

		// 26
		f[25] = growthConsistency;

		Gstr3bFeatureDefinition.validate(f);

		return f;
	}

	private static BigDecimal get(Return3BSummaryBean bean, Function<Return3BSummaryBean, BigDecimal> function) {

		return bean == null ? null : function.apply(bean);
	}

	private static BigDecimal liability(Return3BSummaryBean bean) {

		if (bean == null) {
			return null;
		}

		return safe(bean.getTotalOutputTax()).add(safe(bean.getRcmTotalTax()));
	}

	private static float growth(BigDecimal current, BigDecimal previous) {

		return GrowthUtil.growthFloat(current, previous);
	}

	private static float ratio(BigDecimal numerator, BigDecimal denominator) {

		if (numerator == null || denominator == null || denominator.signum() == 0) {

			return Float.NaN;
		}

		return numerator.divide(denominator, 8, RoundingMode.HALF_UP).floatValue();
	}

	private static BigDecimal safe(BigDecimal value) {

		return value == null ? BigDecimal.ZERO : value;
	}
}
