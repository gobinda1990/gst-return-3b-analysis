package gov.com.ai.webapp.exception;

public class GstDataAccessException extends RuntimeException {
    /**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	public GstDataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}