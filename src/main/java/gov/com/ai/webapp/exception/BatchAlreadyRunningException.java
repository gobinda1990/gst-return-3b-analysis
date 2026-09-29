package gov.com.ai.webapp.exception;

public class BatchAlreadyRunningException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public BatchAlreadyRunningException(String m) {
		super(m);
	}

	public BatchAlreadyRunningException(String m, Throwable t) {
		super(m, t);
	}
}
