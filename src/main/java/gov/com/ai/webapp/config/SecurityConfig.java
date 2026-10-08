package gov.com.ai.webapp.config;

import java.util.List;
import java.util.stream.Collectors;
import jakarta.servlet.http.Cookie;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;


@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

	private final JwtDecoderConfig jwtDecoderConfig;
	private final UnauthorizedEntryPoint unauthorizedEntryPoint;
	private final CustomAccessDeniedHandler accessDeniedHandler;
	private final CustomJwtConverter customJwtConverter;

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		log.info("Initializing SecurityFilterChain configuration");

		http
				// Disable unneeded defaults for REST APIs
				.csrf(csrf -> csrf.disable()).formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())

				// Exception handling
				.exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorizedEntryPoint)
						.accessDeniedHandler(accessDeniedHandler))

				// Security headers
				.headers(headers -> {
					headers.httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000));
					headers.frameOptions(frame -> frame.deny());
					headers.referrerPolicy(ref -> ref.policy(
							org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER));
					headers.addHeaderWriter(new StaticHeadersWriter("X-Content-Type-Options", "nosniff"));
					headers.addHeaderWriter(new StaticHeadersWriter("X-Frame-Options", "DENY"));
					headers.addHeaderWriter(new StaticHeadersWriter("X-XSS-Protection", "1; mode=block"));
					headers.addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
							"geolocation=(), microphone=(), camera=(), fullscreen=(), payment=()"));
					headers.addHeaderWriter(new StaticHeadersWriter("Content-Security-Policy",
							"default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; frame-ancestors 'none';"));
				})

				// Stateless session management
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

				// CORS config
				.cors(cors -> cors.configurationSource(corsConfigurationSource()))

				// Authorization rules
				.authorizeHttpRequests(auth -> 
				auth.requestMatchers("/public/**", "/actuator/**", "/dashboard/ws-meeting", "/dashboard/ws-meeting/**",
						"/dashboard/signup/**","/dashboard/forgot-password/**","/dashboard/profile/**","/uploads/profile-pics/**").permitAll()
						.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll().anyRequest().authenticated())

				// JWT-based OAuth2 resource server
				.oauth2ResourceServer(oauth2 -> oauth2.bearerTokenResolver(bearerTokenResolver())
						.jwt(jwt -> jwt.decoder(jwtDecoderConfig.jwtDecoder())
								.jwtAuthenticationConverter(jwtAuthenticationConverter())));

		return http.build();
	}

	@Bean
	public BearerTokenResolver bearerTokenResolver() {
		return request -> {
			// Header token preferred (standard)
			String authHeader = request.getHeader("Authorization");
			if (authHeader != null && authHeader.startsWith("Bearer ")) {
				return authHeader.substring(7);
			}

			// Fallback: token from cookie (optional)
			if (request.getCookies() != null) {
				for (Cookie cookie : request.getCookies()) {
					if ("access_token".equals(cookie.getName())) {
						return cookie.getValue();
					}
				}
			}
			return null;
		};
	}

	@Bean
	public JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setPrincipalClaimName("sub");
		converter.setJwtGrantedAuthoritiesConverter(jwt -> {
			var userDetails = customJwtConverter.convert(jwt);
			return userDetails.getAuthorities().stream().map(a -> (GrantedAuthority) a).collect(Collectors.toList());
		});
		return converter;
	}

	@Bean
	public UrlBasedCorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();

		// Use specific origins for production (not "*")
		configuration.setAllowedOriginPatterns(List.of("*"));
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("*"));
		configuration.setAllowCredentials(true);
		configuration.setExposedHeaders(List.of("Authorization", "Set-Cookie"));

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}
	
	@Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
