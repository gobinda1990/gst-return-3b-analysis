package gov.com.ai.webapp.service.defaulter;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaulterAuditService {
    private final JdbcTemplate jdbcTemplate;

    public void record(Long proceedingId, String gstin, String period,
            String action, String oldStatus, String newStatus,
            String reference, String officer, String remarks) {
        jdbcTemplate.update("""
            INSERT INTO GST_DEFAULTER_ACTION_AUDIT
            (ID, PROCEEDING_ID, GSTIN, RET_PERIOD, ACTION_TYPE,
             OLD_STATUS, NEW_STATUS, REFERENCE_NO, OFFICER_HRMS, REMARKS, ACTION_AT)
            VALUES
            (SEQ_GST_DEF_AUDIT.NEXTVAL, ?, ?, ?, ?, ?, ?, ?, ?, ?, SYSTIMESTAMP)
            """,
            proceedingId, gstin, period, action, oldStatus, newStatus,
            reference, officer, remarks);
    }
}
