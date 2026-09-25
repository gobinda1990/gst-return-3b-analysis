package gov.com.ai.webapp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.util.Arrays;

/**
 * Global CORS configuration, applied to every path the DispatcherServlet
 * sees - including ones with no matching controller (e.g. a 404 caused by a
 * frontend/backend base-path mismatch, or a request that fails before
 * reaching any @RequestMapping).
 *
 * Why this replaces the controller-level @CrossOrigin: @CrossOrigin only
 * attaches CORS headers to responses that a matching, CORS-annotated
 * controller actually produces. A request that doesn't match any mapping
 * falls through to Spring Boot's default error handling instead, which
 * @CrossOrigin never touches - so that response goes out with no CORS
 * headers at all. The browser can't read a cross-origin response with
 * missing CORS headers, so it reports the whole thing as a generic network
 * failure, indistinguishable from the server actually being unreachable,
 * even though it responded (typically with 404). Registering CORS at this
 * level instead covers every path, matched or not, so the frontend always
 * gets back a response it can actually read and report accurately.
 *
 * app.cors.allowed-origins accepts a comma-separated list, e.g.:
 *   app.cors.allowed-origins=https://gst-growth.example.gov.in,https://staging.example.gov.in
 * Defaults to the local Vite dev server if unset.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

	@Value("${app.cors.allowed-origins:http://localhost:5173}")
	private String allowedOrigins;

	@Override
	public void addCorsMappings(CorsRegistry registry) {

		registry.addMapping("/**")
				.allowedOrigins(Arrays.stream(allowedOrigins.split(","))
						.map(String::trim)
						.filter(origin -> !origin.isEmpty())
						.toArray(String[]::new))
				.allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
				.allowedHeaders("*")
				/*
				 * Content-Disposition exposed so the frontend can eventually
				 * read the export filename off the response if it stops
				 * going through the data-only interceptor for that call.
				 */
				.exposedHeaders("X-Request-Id", "Content-Disposition")
				.allowCredentials(false)
				.maxAge(3600);
	}
}