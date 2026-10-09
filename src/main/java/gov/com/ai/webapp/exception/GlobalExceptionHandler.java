package gov.com.ai.webapp.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The single REST exception handler for the application (replaces the former GlobalExceptionHandler and
 * DefaulterExceptionHandler; two advices that both handle {@code Exception} leave the winner undefined).
 *
 * <p>Every response is JSON: {@code timestamp, status, error, code, message, path}.
 *
 * <p>Rules:
 * <ul>
 *   <li>4xx are the caller's fault: logged at WARN without a stack trace, with the (safe) message returned.</li>
 *   <li>5xx are ours: logged at ERROR with the stack trace; the client only gets a generic message, never SQL,
 *       class names or internals.</li>
 *   <li>A client that disconnected mid-response ({@link AsyncRequestNotUsableException}, Tomcat's
 *       ClientAbortException) is not an error: DEBUG only, nothing is written.</li>
 *   <li>If the response is already committed (a streaming CSV that failed half way) no body can be written, so
 *       the failure is logged and the handler returns nothing.</li>
 *   <li>The body is always sent as application/json, so a request with {@code Accept: text/csv} that fails still
 *       gets a readable error instead of a second, silent failure.</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final int MAX_DETAILS = 5;

	// =========================================================================
	// 400 - domain validation
	// =========================================================================

	@ExceptionHandler(DashboardRequestException.class)
	public ResponseEntity<Map<String, Object>> handleDashboardRequest(DashboardRequestException ex,
			HttpServletRequest request) {

		log.warn("Invalid dashboard request path={} message={}", request.getRequestURI(), ex.getMessage());

		return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_DASHBOARD_REQUEST",
				safeMessage(ex.getMessage(), "Invalid dashboard request."), request);
	}

	@ExceptionHandler(RevenueRequestException.class)
	public ResponseEntity<Map<String, Object>> handleRevenueRequest(RevenueRequestException ex,
			HttpServletRequest request) {

		log.warn("Invalid revenue request path={} message={}", request.getRequestURI(), ex.getMessage());

		return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_REVENUE_REQUEST",
				safeMessage(ex.getMessage(), "Invalid revenue request."), request);
	}

	@ExceptionHandler(DefaulterException.class)
	public ResponseEntity<Map<String, Object>> handleDefaulter(DefaulterException ex, HttpServletRequest request) {

		log.warn("Invalid defaulter request path={} message={}", request.getRequestURI(), ex.getMessage());

		return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_DEFAULTER_REQUEST",
				safeMessage(ex.getMessage(), "Invalid defaulter request."), request);
	}

	/** Parameter-level @Pattern/@Size/@Min/@Max violations (class-level @Validated). */
	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex,
			HttpServletRequest request) {

		// only "param message": the raw exception message leaks method and class names
		String message = ex.getConstraintViolations().stream().map(GlobalExceptionHandler::describe).sorted()
				.limit(MAX_DETAILS).collect(Collectors.joining("; "));

		log.warn("Request validation failed path={} message={}", request.getRequestURI(), message);

		return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
				safeMessage(message, "Request validation failed."), request);
	}

	/** @Valid request bodies / @ModelAttribute binding (MethodArgumentNotValidException extends BindException). */
	@ExceptionHandler(BindException.class)
	public ResponseEntity<Map<String, Object>> handleBind(BindException ex, HttpServletRequest request) {

		String message = ex.getBindingResult().getFieldErrors().stream()
				.map(e -> e.getField() + ": " + e.getDefaultMessage()).limit(MAX_DETAILS)
				.collect(Collectors.joining("; "));

		log.warn("Request binding failed path={} message={}", request.getRequestURI(), message);

		return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
				safeMessage(message, "Request validation failed."), request);
	}

	/** Spring 6.1+ method validation of controller parameters (when the class is not @Validated). */
	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<Map<String, Object>> handleMethodValidation(HandlerMethodValidationException ex,
			HttpServletRequest request) {

		String message = ex.getAllErrors().stream().map(MessageSourceResolvable::getDefaultMessage)
				.filter(Objects::nonNull).limit(MAX_DETAILS).collect(Collectors.joining("; "));

		log.warn("Request validation failed path={} message={}", request.getRequestURI(), message);

		return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
				safeMessage(message, "Request validation failed."), request);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
			HttpServletRequest request) {

		log.warn("Parameter type mismatch path={} parameter={}", request.getRequestURI(), ex.getName());

		return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Invalid value for parameter '"
				+ safeMessage(ex.getName(), "unknown") + "'.", request);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex,
			HttpServletRequest request) {

		log.warn("Unreadable request body path={} cause={}", request.getRequestURI(), ex.getMessage());

		// the parser message can echo request content, so it is not returned
		return buildResponse(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request body.", request);
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex,
			HttpServletRequest request) {

		log.warn("Illegal argument path={} message={}", request.getRequestURI(), ex.getMessage());

		return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT",
				safeMessage(ex.getMessage(), "Invalid request argument."), request);
	}

	// =========================================================================
	// 401 / 403 - security exceptions thrown from controllers and method security.
	// Without these, the catch-all below would turn them into 500s.
	// =========================================================================

	@ExceptionHandler(AuthenticationException.class)
	public ResponseEntity<Map<String, Object>> handleAuthentication(AuthenticationException ex,
			HttpServletRequest request) {

		log.warn("Authentication failed path={} message={}", request.getRequestURI(), ex.getMessage());

		return buildResponse(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required.", request);
	}

	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex,
			HttpServletRequest request) {

		log.warn("Access denied path={} message={}", request.getRequestURI(), ex.getMessage());

		return buildResponse(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
				"You are not allowed to perform this action.", request);
	}

	// =========================================================================
	// 404 / 409 - domain
	// =========================================================================

	@ExceptionHandler(DefaulterNotFoundException.class)
	public ResponseEntity<Map<String, Object>> handleDefaulterNotFound(DefaulterNotFoundException ex,
			HttpServletRequest request) {

		log.warn("Defaulter not found path={} message={}", request.getRequestURI(), ex.getMessage());

		return buildResponse(HttpStatus.NOT_FOUND, "DEFAULTER_NOT_FOUND",
				safeMessage(ex.getMessage(), "Defaulter not found."), request);
	}

	@ExceptionHandler(IllegalProceedingStateException.class)
	public ResponseEntity<Map<String, Object>> handleIllegalProceedingState(IllegalProceedingStateException ex,
			HttpServletRequest request) {

		log.warn("Illegal proceeding state path={} message={}", request.getRequestURI(), ex.getMessage());

		return buildResponse(HttpStatus.CONFLICT, "ILLEGAL_PROCEEDING_STATE",
				safeMessage(ex.getMessage(), "The proceeding is not in a valid state for this action."), request);
	}

	@ExceptionHandler(BatchAlreadyRunningException.class)
	public ResponseEntity<Map<String, Object>> handleBatchAlreadyRunning(BatchAlreadyRunningException ex,
			HttpServletRequest request) {

		log.warn("Batch already running path={} message={}", request.getRequestURI(), ex.getMessage());

		return buildResponse(HttpStatus.CONFLICT, "BATCH_ALREADY_RUNNING",
				safeMessage(ex.getMessage(), "Batch processing is already running."), request);
	}

	// =========================================================================
	// Spring MVC's own errors: missing parameter (400), no such URL (404), wrong method (405, with Allow header),
	// unsupported / unacceptable media type (415 / 406), ResponseStatusException (any status, e.g. the 401 and 429
	// thrown by the controllers), async timeout (503).
	// =========================================================================

	@ExceptionHandler({ ServletRequestBindingException.class, MissingServletRequestPartException.class,
			NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class,
			HttpMediaTypeNotSupportedException.class, HttpMediaTypeNotAcceptableException.class,
			AsyncRequestTimeoutException.class, ErrorResponseException.class })
	public ResponseEntity<Map<String, Object>> handleSpringError(Exception ex, HttpServletRequest request,
			HttpServletResponse response) {

		ErrorResponse er = (ErrorResponse) ex;
		HttpStatus status = toStatus(er.getStatusCode().value());

		if (status.is5xxServerError()) {
			log.error("Request failed path={} status={}", request.getRequestURI(), status.value(), ex);
			if (response.isCommitted()) {
				return null; // e.g. async timeout during a streaming export: nothing can be written any more
			}
		} else {
			log.warn("Request rejected path={} status={} message={}", request.getRequestURI(), status.value(),
					ex.getMessage());
		}

		String detail = er.getBody().getDetail();

		return buildResponse(status, status.name(), safeMessage(detail, status.getReasonPhrase()), request,
				er.getHeaders());
	}

	// =========================================================================
	// 5xx
	// =========================================================================

	@ExceptionHandler(RevenueBatchException.class)
	public ResponseEntity<Map<String, Object>> handleRevenueBatch(RevenueBatchException ex,
			HttpServletRequest request, HttpServletResponse response) {

		log.error("Revenue batch processing failed path={} message={}", request.getRequestURI(), ex.getMessage(),
				ex);

		if (response.isCommitted()) {
			return null;
		}

		return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "BATCH_ERROR", "Revenue batch processing failed.",
				request);
	}

	/** Database down, pool exhausted or query timed out: the client should retry, so 503 instead of 500. */
	@ExceptionHandler({ QueryTimeoutException.class, DataAccessResourceFailureException.class,
			TransientDataAccessException.class })
	public ResponseEntity<Map<String, Object>> handleDatabaseUnavailable(DataAccessException ex,
			HttpServletRequest request, HttpServletResponse response) {

		log.error("Database unavailable path={} message={}", request.getRequestURI(), ex.getMessage(), ex);

		if (response.isCommitted()) {
			return null;
		}

		return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE",
				"The service is temporarily unavailable. Please retry shortly.", request);
	}

	@ExceptionHandler(DataAccessException.class)
	public ResponseEntity<Map<String, Object>> handleDatabase(DataAccessException ex, HttpServletRequest request,
			HttpServletResponse response) {

		log.error("Database error path={} message={}", request.getRequestURI(), ex.getMessage(), ex);

		if (response.isCommitted()) {
			return null;
		}

		// never return SQL / database details to the frontend
		return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "DATABASE_ERROR",
				"Unable to process database request.", request);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex, HttpServletRequest request,
			HttpServletResponse response) {

		// The browser/proxy closed the connection while the response was being written (tab closed, request
		// cancelled, navigation, gateway timeout). Nothing is wrong server-side and nobody is listening.
		if (isClientAbort(ex)) {
			log.debug("Client disconnected before the response was written path={} message={}",
					request.getRequestURI(), ex.getMessage());
			return null;
		}

		log.error("Unexpected application error path={} message={}", request.getRequestURI(), ex.getMessage(), ex);

		if (response.isCommitted()) {
			return null; // e.g. a streaming export that failed half way: the client sees a truncated file
		}

		return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error.",
				request);
	}

	// =========================================================================
	// helpers
	// =========================================================================

	private static boolean isClientAbort(Throwable ex) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (t instanceof AsyncRequestNotUsableException
					|| "ClientAbortException".equals(t.getClass().getSimpleName())) {
				return true;
			}
			if (t.getCause() == t) {
				break;
			}
		}
		return false;
	}

	private static String describe(ConstraintViolation<?> v) {

		String path = v.getPropertyPath().toString();
		int dot = path.lastIndexOf('.');
		String name = dot >= 0 ? path.substring(dot + 1) : path;

		return name + " " + v.getMessage();
	}

	private static HttpStatus toStatus(int code) {
		HttpStatus status = HttpStatus.resolve(code);
		return status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
	}

	private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String code, String message,
			HttpServletRequest request) {
		return buildResponse(status, code, message, request, null);
	}

	private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String code, String message,
			HttpServletRequest request, HttpHeaders extraHeaders) {

		Map<String, Object> body = new LinkedHashMap<>();

		body.put("timestamp", OffsetDateTime.now().toString());
		body.put("status", status.value());
		body.put("error", status.getReasonPhrase());
		body.put("code", code);
		body.put("message", safeMessage(message, "Request failed."));
		body.put("path", request != null ? request.getRequestURI() : "");

		ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);

		if (extraHeaders != null && !extraHeaders.isEmpty()) {
			builder.headers(h -> h.addAll(extraHeaders)); // e.g. Allow on a 405
		}

		return builder.body(body);
	}

	private static String safeMessage(String message, String fallback) {

		if (message == null || message.isBlank()) {
			return fallback;
		}

		return message.trim();
	}
}