package gov.com.ai.webapp.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class DefaulterExceptionHandler {

    @ExceptionHandler(DefaulterNotFoundException.class)
    public ResponseEntity<Map<String,Object>> notFound(
            DefaulterNotFoundException ex, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalProceedingStateException.class)
    public ResponseEntity<Map<String,Object>> conflict(
            IllegalProceedingStateException ex, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(DefaulterException.class)
    public ResponseEntity<Map<String,Object>> invalid(
            DefaulterException ex, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }  

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String,Object>> validation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .orElse("Request validation failed");
        return response(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String,Object>> database(
            DataAccessException ex, HttpServletRequest request) {
        log.error("Database error path={}", request.getRequestURI(), ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR,
            "Database operation failed", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,Object>> unexpected(
            Exception ex, HttpServletRequest request) {
        log.error("Unexpected error path={}", request.getRequestURI(), ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal server error", request);
    }

    private ResponseEntity<Map<String,Object>> response(
            HttpStatus status, String message, HttpServletRequest request) {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("path", request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
