package gov.com.ai.webapp.exception;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

/**
 * Global REST exception handler for the application.
 *
 * Handles: - Dashboard validation errors - Revenue validation errors - Jakarta
 * validation errors - Batch conflicts - Batch processing errors - Database
 * errors - Unexpected application errors
 *
 * Provides a consistent JSON error response for all REST endpoints.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	/*
	 * ========================================================= BAD REQUEST -
	 * DASHBOARD =========================================================
	 */

	@ExceptionHandler(DashboardRequestException.class)
	public ResponseEntity<Map<String, Object>> handleDashboardRequestException(DashboardRequestException ex,
			HttpServletRequest request) {

		log.warn("Invalid dashboard request path={} message={}", request.getRequestURI(), ex.getMessage());

		return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_DASHBOARD_REQUEST",
				safeMessage(ex.getMessage(), "Invalid dashboard request."), request);
	}

	/*
	 * ========================================================= BAD REQUEST -
	 * REVENUE =========================================================
	 */

	@ExceptionHandler(RevenueRequestException.class)
	public ResponseEntity<Map<String, Object>> handleRevenueRequestException(RevenueRequestException ex,
			HttpServletRequest request) {

		log.warn("Invalid revenue request path={} message={}", request.getRequestURI(), ex.getMessage());

		return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_REVENUE_REQUEST",
				safeMessage(ex.getMessage(), "Invalid revenue request."), request);
	}

	/*
	 * ========================================================= BAD REQUEST -
	 * VALIDATION =========================================================
	 */

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex,
			HttpServletRequest request) {

		log.warn("Request validation failed path={} message={}", request.getRequestURI(), ex.getMessage());

		return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
				safeMessage(ex.getMessage(), "Request validation failed."), request);
	}

	/*
	 * ========================================================= BAD REQUEST -
	 * ILLEGAL ARGUMENT =========================================================
	 */

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex,
			HttpServletRequest request) {

		log.warn("Illegal argument path={} message={}", request.getRequestURI(), ex.getMessage());

		return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT",
				safeMessage(ex.getMessage(), "Invalid request argument."), request);
	}

	/*
	 * ========================================================= CONFLICT - BATCH
	 * ALREADY RUNNING =========================================================
	 */

	@ExceptionHandler(BatchAlreadyRunningException.class)
	public ResponseEntity<Map<String, Object>> handleBatchAlreadyRunning(BatchAlreadyRunningException ex,
			HttpServletRequest request) {

		log.warn("Batch already running path={} message={}", request.getRequestURI(), ex.getMessage());

		return buildResponse(HttpStatus.CONFLICT, "BATCH_ALREADY_RUNNING",
				safeMessage(ex.getMessage(), "Batch processing is already running."), request);
	}

	/*
	 * ========================================================= INTERNAL ERROR -
	 * REVENUE BATCH =========================================================
	 */

	@ExceptionHandler(RevenueBatchException.class)
	public ResponseEntity<Map<String, Object>> handleRevenueBatchException(RevenueBatchException ex,
			HttpServletRequest request) {

		log.error("Revenue batch processing failed path={} message={}", request.getRequestURI(), ex.getMessage(), ex);

		return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "BATCH_ERROR", "Revenue batch processing failed.",
				request);
	}

	/*
	 * ========================================================= INTERNAL ERROR -
	 * DATABASE =========================================================
	 */

	@ExceptionHandler(DataAccessException.class)
	public ResponseEntity<Map<String, Object>> handleDatabaseException(DataAccessException ex,
			HttpServletRequest request) {

		log.error("Database error path={} message={}", request.getRequestURI(), ex.getMessage(), ex);

		/*
		 * Do not return SQL/database details to the frontend.
		 */
		return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "DATABASE_ERROR", "Unable to process database request.",
				request);
	}

	/*
	 * ========================================================= INTERNAL ERROR -
	 * FALLBACK =========================================================
	 */

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, Object>> handleUnexpectedException(Exception ex, HttpServletRequest request) {

		log.error("Unexpected application error path={} message={}", request.getRequestURI(), ex.getMessage(), ex);

		return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error.", request);
	}

	/*
	 * ========================================================= COMMON RESPONSE
	 * BUILDER =========================================================
	 */

	private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String code, String message,
			HttpServletRequest request) {

		Map<String, Object> body = new LinkedHashMap<>();

		body.put("timestamp", OffsetDateTime.now().toString());

		body.put("status", status.value());

		body.put("error", status.getReasonPhrase());

		body.put("code", code);

		body.put("message", safeMessage(message, "Request failed."));

		body.put("path", request != null ? request.getRequestURI() : "");

		return ResponseEntity.status(status).body(body);
	}

	/*
	 * ========================================================= SAFE MESSAGE
	 * =========================================================
	 */

	private String safeMessage(String message, String fallback) {

		if (message == null || message.isBlank()) {
			return fallback;
		}

		return message.trim();
	}
}