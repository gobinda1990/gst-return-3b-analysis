package gov.com.ai.webapp.model;



import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReturnPeriodOptionDto {
    private String label; // e.g., "June 2026"
    private String value; // e.g., "062026"
}
