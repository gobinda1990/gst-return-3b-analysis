package gov.com.ai.webapp.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class Gstr3bFilingRepository {
    private final JdbcTemplate jdbcTemplate;

    public boolean hasFiled(String gstin, String retPeriod) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(1)
            FROM GST_RET_3B_SUMMARY
            WHERE TRIM(GSTIN) = ?
              AND RET_PERIOD = ?
              AND FILING_DATE IS NOT NULL
            """, Integer.class, gstin, retPeriod);
        return count != null && count > 0;
    }

    public Optional<LocalDate> findFilingDate(String gstin, String retPeriod) {
        Date date = jdbcTemplate.queryForObject("""
            SELECT MAX(FILING_DATE)
            FROM GST_RET_3B_SUMMARY
            WHERE TRIM(GSTIN) = ?
              AND RET_PERIOD = ?
              AND FILING_DATE IS NOT NULL
            """, Date.class, gstin, retPeriod);
        return Optional.ofNullable(date).map(Date::toLocalDate);
    }
}
