package gov.com.ai.webapp.service;

public final class Gstr3bFeatureDefinition {

	private Gstr3bFeatureDefinition() {
	}

	public static final String MODEL_VERSION = "GSTR3B-XGB-26-V1";
	public static final int FEATURE_COUNT = 26;

	public static final String[] FEATURE_NAMES = { "taxableValue", "outputTax", "eligibleItc", "utilizedItc",
			"cashTaxPaid",

			"itcUtilizationRatio", "itcToTaxRatio", "cashPaymentRatio", "rcmToTaxRatio", "rcmItcRatio", "rcmCashRatio",

			"filingDelayDays",

			"taxableValueGrowth1M", "taxableValueGrowth3M", "taxableValueGrowth6M", "taxableValueGrowth12M",

			"outputTaxGrowth3M", "outputTaxGrowth6M",

			"itcGrowth3M", "itcGrowth6M",

			"cashTaxGrowth3M", "cashTaxGrowth6M",

			"liabilityGrowth3M", "liabilityGrowth6M",

			"growthVolatility", "growthConsistency" };

	static {
		if (FEATURE_NAMES.length != FEATURE_COUNT) {
			throw new IllegalStateException(
					"Expected " + FEATURE_COUNT + " features but found " + FEATURE_NAMES.length);
		}
	}

	public static void validate(float[] features) {

		if (features == null) {
			throw new IllegalArgumentException("Feature vector cannot be null");
		}

		if (features.length != FEATURE_COUNT) {
			throw new IllegalArgumentException(
					"Invalid feature count. Expected=" + FEATURE_COUNT + ", actual=" + features.length);
		}
	}
}
