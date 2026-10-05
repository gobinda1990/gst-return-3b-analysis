package gov.com.ai.webapp.service.defaulter;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class Section62LateFeeVerifierImpl implements Section62LateFeeVerifier {
    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean additionalLateFeeSatisfied(String gstin, String retPeriod) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(1)
              FROM VW_GST_SECTION62_LATE_FEE_STATUS
             WHERE GSTIN = ?
               AND RET_PERIOD = ?
               AND ADDITIONAL_LATE_FEE_SATISFIED = 'Y'
            """, Integer.class, gstin, retPeriod);
        return count != null && count > 0;
    }
}
