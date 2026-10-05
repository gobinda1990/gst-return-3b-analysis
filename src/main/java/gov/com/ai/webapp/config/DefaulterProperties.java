package gov.com.ai.webapp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "gst.defaulter")
public class DefaulterProperties {
    private int scanBatchSize = 1000;
    private int maxBatchSize = 5000;
    private int queryTimeoutSeconds = 30;
}
