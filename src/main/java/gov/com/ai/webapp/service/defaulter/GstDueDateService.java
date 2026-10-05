package gov.com.ai.webapp.service.defaulter;

import gov.com.ai.webapp.config.GstLegalConstants;
import gov.com.ai.webapp.exception.DefaulterException;
import gov.com.ai.webapp.repository.GstDueDateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class GstDueDateService {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("MMuuuu");
    private final GstDueDateRepository repository;

    public LocalDate resolve(String period, String frequency, String category) {
        validate(period);
        return repository.findEffectiveDueDate(
                GstLegalConstants.RETURN_GSTR3B, period, frequency, category)
            .orElseGet(() -> fallback(period, frequency));
    }

    private LocalDate fallback(String period, String frequency) {
        if (!"MONTHLY".equalsIgnoreCase(frequency)) {
            throw new DefaulterException(
                "No statutory due date configured for period " + period +
                " and frequency " + frequency);
        }
        return YearMonth.parse(period, FORMAT).plusMonths(1).atDay(20);
    }

    private void validate(String period) {
        if (period == null || !period.matches("^(0[1-9]|1[0-2])\\d{4}$")) {
            throw new DefaulterException("Invalid return period: " + period);
        }
    }
}
