package gov.com.ai.webapp.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@Slf4j
@RestControllerAdvice
public class GrowthExceptionHandler {

	@ExceptionHandler(InvalidGrowthRequestException.class)
	public ResponseEntity<ApiErrorResponse> invalidRequest(InvalidGrowthRequestException ex,
			HttpServletRequest request) {

		log.warn("Invalid GST growth request path={} reason={}", request.getRequestURI(), ex.getMessage());

		return response(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
	}

	@ExceptionHandler({ MissingServletRequestParameterException.class, MethodArgumentNotValidException.class })
	public ResponseEntity<ApiErrorResponse> validation(Exception ex, HttpServletRequest request) {

		log.warn("GST growth validation failure path={} type={}", request.getRequestURI(),
				ex.getClass().getSimpleName());

		return response(HttpStatus.BAD_REQUEST, "Invalid request parameters", request);
	}

	@ExceptionHandler(GrowthDataAccessException.class)
	public ResponseEntity<ApiErrorResponse> database(GrowthDataAccessException ex, HttpServletRequest request) {

		log.error("GST growth data operation failed path={}", request.getRequestURI(), ex);

		return response(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to process GST growth data", request);
	}

	@ExceptionHandler(DataAccessException.class)
	public ResponseEntity<ApiErrorResponse> springDatabase(DataAccessException ex, HttpServletRequest request) {

		log.error("Database failure path={}", request.getRequestURI(), ex);

		return response(HttpStatus.INTERNAL_SERVER_ERROR, "Database operation failed", request);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiErrorResponse> unexpected(Exception ex, HttpServletRequest request) {

		log.error("Unexpected GST growth failure path={}", request.getRequestURI(), ex);

		return response(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error", request);
	}

	private ResponseEntity<ApiErrorResponse> response(HttpStatus status, String message, HttpServletRequest request) {

		return ResponseEntity.status(status)
				.body(ApiErrorResponse.builder().timestamp(Instant.now()).status(status.value())
						.error(status.getReasonPhrase()).message(message).path(request.getRequestURI())
						.correlationId(MDC.get("correlationId")).build());
	}
}