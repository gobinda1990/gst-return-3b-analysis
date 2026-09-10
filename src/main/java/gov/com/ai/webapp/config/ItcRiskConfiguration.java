package gov.com.ai.webapp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ItcRiskProperties.class)
public class ItcRiskConfiguration {
}