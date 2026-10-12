package gov.com.ai.webapp.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import gov.com.ai.webapp.model.revenue.OptionDto;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class Gstr3bFilingRepository {
	
	private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("MMuuuu");

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
        try {
            Date date = jdbcTemplate.queryForObject("""
                SELECT MAX(FILING_DATE)
                FROM GST_RET_3B_SUMMARY
                WHERE TRIM(GSTIN) = ?
                  AND RET_PERIOD = ?
                  AND FILING_DATE IS NOT NULL
                """, Date.class, gstin, retPeriod);
            
            return Optional.ofNullable(date).map(Date::toLocalDate);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
    
    public List<OptionDto> periods() {
        String sql = """
                SELECT DISTINCT RET_PERIOD
                FROM GST_3B_DEFAULTER_PROCEEDING
                ORDER BY TO_DATE(RET_PERIOD, 'MMYYYY') DESC
                """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String value = rs.getString("RET_PERIOD");
            return new OptionDto(value, periodLabel(value));
        });
    }

    private String periodLabel(String period) {
		try {
			YearMonth ym = YearMonth.parse(period, PERIOD_FORMAT);
			return ym.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + ym.getYear();
		} catch (RuntimeException ex) {
		//	log.warn("Invalid return period encountered in dashboard table: {}", period);
			return period;
		}
	}
}