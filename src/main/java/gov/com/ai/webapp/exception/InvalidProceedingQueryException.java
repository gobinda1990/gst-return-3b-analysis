package gov.com.ai.webapp.exception;

public class InvalidProceedingQueryException extends RuntimeException {
    private static final long serialVersionUID = 1L;

	public InvalidProceedingQueryException(String message) {
        super(message);
    }
}
