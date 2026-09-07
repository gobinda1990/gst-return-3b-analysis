package gov.com.ai.webapp.manager;

import lombok.Getter;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Registry Enum for ML Models across the GSTR-3B Risk Assessment Subsystem.
 * Encapsulates model identifiers, binary export filenames, objective functions,
 * primary metrics, and target variable extraction functions.
 */
@Getter
public enum ModelType implements Serializable {

	/**
	 * Output Tax Liability Projection Model.
	 */
	OUTPUT_TAX("output-tax", "output-tax.model", "reg:squarederror", "rmse", true,
			bean -> bean != null && bean.getTotalOutputTax() != null ? bean.getTotalOutputTax().doubleValue() : 0.0),

	/**
	 * Input Tax Credit (ITC) Claimed Prediction Model.
	 */
	ITC("itc", "itc.model", "reg:squarederror", "rmse", true,
			bean -> bean != null && bean.getUtilizedItc() != null ? bean.getUtilizedItc().doubleValue() : 0.0),

	/**
	 * Cash Tax Liability Estimate Model.
	 */
	CASH_TAX("cash-tax", "cash-tax.model", "reg:squarederror", "rmse", true,
			bean -> bean != null && bean.getCashTaxPaid() != null ? bean.getCashTaxPaid().doubleValue() : 0.0),

	/**
	 * Filing Delay Prediction Model.
	 */
	DELAY("delay", "delay.model", "reg:squarederror", "rmse", true,
			bean -> bean != null && bean.getFilingDelayDays() != null ? bean.getFilingDelayDays().doubleValue() : 0.0),

	/**
	 * Binary Logistic Classification Model for Fraud / Risk Flag Detection.
	 */
	RISK("risk", "risk.model", "binary:logistic", "logloss", false, bean -> {
		if (bean == null)
			return 0.0;
		if (bean.getFraudLabel() != null)
			return bean.getFraudLabel().doubleValue();

		boolean isSuspicious = Boolean.TRUE.equals(bean.getLateFiled()) && bean.getFilingDelayDays() != null
				&& bean.getFilingDelayDays() > 30;
		boolean isExcessItc = bean.getExcessItc() != null && bean.getExcessItc().compareTo(new BigDecimal("50000")) > 0;
		return (isSuspicious || isExcessItc) ? 1.0 : 0.0;
	}),

	/*
	 * ===================================================== LEGACY ALIASES (Safely
	 * reference target extractors)
	 * =====================================================
	 */
	FRAUD_RISK("FRAUD_RISK", "fraud_risk_v1.model", "binary:logistic", "auc", false, bean -> {
		if (bean == null)
			return 0.0;
		if (bean.getFraudLabel() != null)
			return bean.getFraudLabel().doubleValue();
		boolean isSuspicious = Boolean.TRUE.equals(bean.getLateFiled()) && bean.getFilingDelayDays() != null
				&& bean.getFilingDelayDays() > 30;
		boolean isExcessItc = bean.getExcessItc() != null && bean.getExcessItc().compareTo(new BigDecimal("50000")) > 0;
		return (isSuspicious || isExcessItc) ? 1.0 : 0.0;
	}),

	TAXABLE_VALUE_PROJECTION("TAXABLE_VALUE_PROJECTION", "taxable_value_projection_v1.model", "reg:squarederror",
			"rmse", true,
			bean -> bean != null && bean.getTotalOutputTax() != null ? bean.getTotalOutputTax().doubleValue() : 0.0),

	ITC_UTILIZATION_PREDICTION("ITC_UTILIZATION_PREDICTION", "itc_utilization_v1.model", "reg:squarederror", "rmse",
			true, bean -> bean != null && bean.getUtilizedItc() != null ? bean.getUtilizedItc().doubleValue() : 0.0),

	CASH_LIABILITY_ESTIMATE("CASH_LIABILITY_ESTIMATE", "cash_liability_v1.model", "reg:squarederror", "rmse", true,
			bean -> bean != null && bean.getCashTaxPaid() != null ? bean.getCashTaxPaid().doubleValue() : 0.0);

	private final String modelKey;
	private final String fileName;
	private final String objective;
	private final String evalMetric;
	private final boolean regression;
	private final TrainingDataset.TargetExtractor targetExtractor;

	ModelType(String modelKey, String fileName, String objective, String evalMetric, boolean regression,
			TrainingDataset.TargetExtractor targetExtractor) {
		this.modelKey = modelKey;
		this.fileName = fileName;
		this.objective = objective;
		this.evalMetric = evalMetric;
		this.regression = regression;
		this.targetExtractor = targetExtractor;
	}

	/**
	 * Resolves a ModelType enum by its key string or enum name (case-insensitive,
	 * hyphen/underscore tolerant). Supports "output-tax", "OUTPUT_TAX",
	 * "FRAUD_RISK", "risk", "cash-tax", etc.
	 *
	 * @param key String key
	 * @return Matching ModelType enum
	 * @throws IllegalArgumentException if no matching ModelType exists
	 */
	public static ModelType fromKey(String key) {
		if (key == null || key.trim().isEmpty()) {
			throw new IllegalArgumentException("Model key cannot be null or empty");
		}

		String rawKey = key.trim();
		String normalizedKey = rawKey.toLowerCase();
		String normalizedUnderscore = rawKey.replace("-", "_").toLowerCase();
		String normalizedHyphen = rawKey.replace("_", "-").toLowerCase();

		for (ModelType type : values()) {
			if (type.getModelKey().equalsIgnoreCase(normalizedKey)
					|| type.getModelKey().equalsIgnoreCase(normalizedHyphen)
					|| type.name().equalsIgnoreCase(normalizedKey)
					|| type.name().equalsIgnoreCase(normalizedUnderscore)) {
				return type;
			}
		}
		throw new IllegalArgumentException("Unknown ModelType key: " + key);
	}

	/**
	 * Returns true if the model type is classification.
	 */
	public boolean isClassification() {
		return !isRegression();
	}
}