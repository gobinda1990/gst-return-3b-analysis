package gov.com.ai.webapp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import gov.com.ai.webapp.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Slf4j
@Component
public class UnauthorizedEntryPoint implements AuthenticationEntryPoint {

	private final ObjectMapper mapper;

	public UnauthorizedEntryPoint(ObjectMapper mapper) {
		// Preconfigure ObjectMapper for thread-safe reuse
		this.mapper = mapper.copy().registerModule(new JavaTimeModule())
				.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {

		log.warn("Unauthorized access attempt to {}: {}", request.getServletPath(), authException.getMessage());

		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());

		ErrorResponse body = ErrorResponse.builder().status(HttpServletResponse.SC_UNAUTHORIZED).error("Unauthorized")
				.timestamp(Instant.now()).message("Authentication is required") // Generic message
				.path(request.getServletPath()).build();

		mapper.writeValue(response.getOutputStream(), body);
	}
}
