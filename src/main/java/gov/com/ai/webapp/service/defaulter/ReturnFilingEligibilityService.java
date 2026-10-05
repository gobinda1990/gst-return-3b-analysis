package gov.com.ai.webapp.service.defaulter;

import gov.com.ai.webapp.exception.DefaulterException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class ReturnFilingEligibilityService {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("MMuuuu");
    private final JdbcTemplate jdbcTemplate;

    public FilingRequirement determine(String gstin, String retPeriod) {
        validate(gstin, retPeriod);
        YearMonth period = YearMonth.parse(retPeriod, FORMAT);
        if (!wasRegistered(gstin, period.atEndOfMonth())) {
            return FilingRequirement.notRequired();
        }

        /*
         * INTEGRATION POINT:
         * Replace this MONTHLY default with the application's existing
         * period-aware QRMP/PRF resolver before enabling notices for QRMP cases.
         */
        return new FilingRequirement(true, "MONTHLY", "REGULAR");
    }

    private boolean wasRegistered(String gstin, LocalDate periodEnd) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(1)
            FROM GST_DEALER_MASTER_WBCOMTAX R
            WHERE R.GSTIN = ?
              AND R.REG_ST_DATE <= ?
              AND (R.CANC_DT IS NULL OR R.CANC_DT >= ?)
            """, Integer.class, gstin, Date.valueOf(periodEnd), Date.valueOf(periodEnd));
        return count != null && count > 0;
    }

    private void validate(String gstin, String period) {
        if (gstin == null || gstin.length() != 15) {
            throw new DefaulterException("Invalid GSTIN");
        }
        if (period == null || !period.matches("^(0[1-9]|1[0-2])\\d{4}$")) {
            throw new DefaulterException("Invalid return period");
        }
    }

    public record FilingRequirement(boolean required, String frequency, String category) {
        public static FilingRequirement notRequired() {
            return new FilingRequirement(false, null, null);
        }
    }
}
