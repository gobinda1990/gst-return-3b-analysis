package gov.com.ai.webapp.exception;

public class DefaulterException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public DefaulterException(String message) {
		super(message);
	}

	public DefaulterException(String message, Throwable cause) {
		super(message, cause);
	}
}
