package gov.com.ai.webapp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import gov.com.ai.webapp.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Slf4j
@Component
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

	private final ObjectMapper mapper;

	public CustomAccessDeniedHandler(ObjectMapper mapper) {
		// Use a thread-safe copy of ObjectMapper configured for JavaTime
		this.mapper = mapper.copy().registerModule(new JavaTimeModule())
				.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {

		log.warn("Access denied for path {}: {}", request.getServletPath(), accessDeniedException.getMessage());

		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());

		ErrorResponse body = ErrorResponse.builder().status(HttpServletResponse.SC_FORBIDDEN).error("Forbidden")
				.timestamp(Instant.now()).message("Access is denied") // Generic message to avoid leaking info
				.path(request.getServletPath()).build();

		// Directly write JSON to output stream (avoid extra allocations)
		mapper.writeValue(response.getOutputStream(), body);
	}
}
