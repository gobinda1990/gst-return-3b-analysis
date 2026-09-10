package gov.com.ai.webapp.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gst.itc.risk")
public record ItcRiskProperties(

        BigDecimal mediumAmount,
        BigDecimal highAmount,
        BigDecimal criticalAmount,

        int mediumOccurrences,
        int highOccurrences,
        int criticalOccurrences,

        BigDecimal highUtilizationPercent,
        BigDecimal criticalUtilizationPercent

) {
}