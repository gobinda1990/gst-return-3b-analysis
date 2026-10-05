package gov.com.ai.webapp.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class GstDueDateRepository {
	
    private final JdbcTemplate jdbcTemplate;
    
    private final String DUE_DATE_QUERY=" SELECT EFFECTIVE_DUE_DATE  FROM GST_RETURN_DUE_DATE_MASTER "
    		   + "  WHERE RETURN_TYPE = ? AND RET_PERIOD = ? AND ACTIVE_FLAG = 'Y' ";

    public Optional<LocalDate> findEffectiveDueDate(
            String returnType, String period, String frequency, String category) {
        return jdbcTemplate.query(DUE_DATE_QUERY, rs -> rs.next()
                ? Optional.of(rs.getDate("EFFECTIVE_DUE_DATE").toLocalDate())
                : Optional.empty(),
            returnType, period );
    }
}
