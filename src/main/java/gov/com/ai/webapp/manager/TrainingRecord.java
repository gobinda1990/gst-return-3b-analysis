package gov.com.ai.webapp.manager;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import gov.com.ai.webapp.model.Return3BSummaryBean;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * Enterprise Production DTO representing an individual training data instance.
 * Encapsulates feature vector elements, multi-target label attributes, and metadata.
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TrainingRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // Aligned with TrainingDataset.extractFeatureVector() dimensions
    public static final int EXPECTED_FEATURE_COUNT = 71;

    @JsonProperty("gstin")
    private String gstin;

    @JsonProperty("ret_period")
    private String retPeriod;

    @JsonProperty("features")
    private float[] features;

    // Target labels for multi-model training
    @JsonProperty("target_output_tax")
    private float targetOutputTax;

    @JsonProperty("target_itc")
    private float targetITC;

    @JsonProperty("target_cash_tax")
    private float targetCashTax;

    @JsonProperty("target_delay_days")
    private float targetDelayDays;

    @JsonProperty("target_fraud_risk")
    private float targetFraudRisk;

    @JsonProperty("created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();

    /**
     * Factory method to convert a domain Return3BSummaryBean into a validated TrainingRecord.
     */
    public static TrainingRecord fromBean(Return3BSummaryBean bean) {
        if (bean == null) {
            throw new IllegalArgumentException("Source Return3BSummaryBean cannot be null");
        }

        Return3BSummaryBean.normalize(bean);
        float[] extractedFeatures = TrainingDataset.extractFeatureVector(bean);

        TrainingRecord record = TrainingRecord.builder()
                .gstin(bean.getGstin())
                .retPeriod(bean.getRetPeriod())
                .features(extractedFeatures)
                .targetOutputTax(bean.getTotalOutputTax() != null ? bean.getTotalOutputTax().floatValue() : 0.0f)
                .targetITC(bean.getUtilizedItc() != null ? bean.getUtilizedItc().floatValue() : 0.0f)
                .targetCashTax(bean.getCashTaxPaid() != null ? bean.getCashTaxPaid().floatValue() : 0.0f)
                .targetDelayDays((float) bean.getFilingDelayDays())
                .targetFraudRisk(bean.getRiskScore() != null ? bean.getRiskScore().floatValue() : 0.0f)
                .build();

        record.validateStrict();
        return record;
    }

    /**
     * Validates feature vector consistency and structural compliance.
     */
    public boolean isValid() {
        if (gstin == null || gstin.isBlank() || retPeriod == null || retPeriod.isBlank()) {
            return false;
        }
        if (features == null || features.length != EXPECTED_FEATURE_COUNT) {
            return false;
        }
        for (float f : features) {
            if (Float.isNaN(f) || Float.isInfinite(f)) {
                return false;
            }
        }
        return targetFraudRisk >= 0.0f && targetFraudRisk <= 1.0f;
    }

    /**
     * Performs strict self-validation throwing an exception if invalid.
     */
    public void validateStrict() {
        if (!isValid()) {
            throw new IllegalArgumentException(String.format(
                    "Invalid TrainingRecord state for GSTIN: %s, RetPeriod: %s. Expected %d valid features, got %d.",
                    gstin, retPeriod, EXPECTED_FEATURE_COUNT, features != null ? features.length : 0));
        }
    }

    /**
     * Deep equality check including feature vector elements.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TrainingRecord that = (TrainingRecord) o;
        return Float.compare(that.targetOutputTax, targetOutputTax) == 0
                && Float.compare(that.targetITC, targetITC) == 0
                && Float.compare(that.targetCashTax, targetCashTax) == 0
                && Float.compare(that.targetDelayDays, targetDelayDays) == 0
                && Float.compare(that.targetFraudRisk, targetFraudRisk) == 0
                && Objects.equals(gstin, that.gstin)
                && Objects.equals(retPeriod, that.retPeriod)
                && Arrays.equals(features, that.features);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(gstin, retPeriod, targetOutputTax, targetITC, targetCashTax, targetDelayDays, targetFraudRisk);
        result = 31 * result + Arrays.hashCode(features);
        return result;
    }
}