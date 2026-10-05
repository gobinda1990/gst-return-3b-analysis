package gov.com.ai.webapp.exception;

public class DefaulterException extends RuntimeException {
    public DefaulterException(String message) { super(message); }
    public DefaulterException(String message, Throwable cause) { super(message, cause); }
}
