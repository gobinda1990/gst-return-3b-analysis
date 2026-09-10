package gov.com.ai.webapp.model.dto;

import java.math.BigDecimal;

public record GstMlAnalysis(

        BigDecimal xgbRiskScore,

        BigDecimal anomalyScore,

        String riskLevel
) {
}
