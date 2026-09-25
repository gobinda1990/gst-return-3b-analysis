package gov.com.ai.webapp.exception;

public class InvalidGrowthRequestException
        extends RuntimeException {

    /**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	public InvalidGrowthRequestException(String message) {
        super(message);
    }
}