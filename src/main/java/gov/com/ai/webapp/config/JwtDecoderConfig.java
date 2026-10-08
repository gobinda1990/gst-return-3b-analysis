package gov.com.ai.webapp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;

@Configuration
@Slf4j
public class JwtDecoderConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwkSetUri;

    @Bean
    public JwtDecoder jwtDecoder() {
    	
        log.info("Creating JwtDecoder for JWKS: {}", jwkSetUri);

        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();

        OAuth2TokenValidator<Jwt> defaultValidator = JwtValidators.createDefault();

        OAuth2TokenValidator<Jwt> loggingValidator = token -> {
            var result = defaultValidator.validate(token);
            if (result.hasErrors()) {
                result.getErrors().forEach(error -> log.warn("JWT validation error: {} (sub={}, aud={})",
                        error.getDescription(), token.getSubject(), token.getAudience()));
            } else {
               // log.info("JWT validated successfully (sub={}, aud={})", token.getSubject(), token.getAudience());
            }
            return result;
        };

        jwtDecoder.setJwtValidator(loggingValidator);
        return jwtDecoder;
    }
}
