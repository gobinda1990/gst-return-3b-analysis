package gov.com.ai.webapp.exception;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
@Data
@AllArgsConstructor
@Builder
public class ErrorResponse {
	private int status;
    private String error;
    private Instant timestamp;
    private String message;
    private String path;

}
