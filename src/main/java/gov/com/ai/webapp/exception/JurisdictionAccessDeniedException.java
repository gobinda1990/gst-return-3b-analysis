package gov.com.ai.webapp.exception;

public class JurisdictionAccessDeniedException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public JurisdictionAccessDeniedException(String message) {
		super(message);
	}
}
