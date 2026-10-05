package gov.com.ai.webapp.service.defaulter;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OfficerAssignmentServiceImpl implements OfficerAssignmentService {
    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean hasOfficeAccess(String officerHrmsCode, String officeCode) {
        if (officerHrmsCode == null || officerHrmsCode.isBlank() || officeCode == null || officeCode.isBlank()) return false;
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(1)
              FROM VW_ACTIVE_OFFICER_OFFICE_SCOPE
             WHERE HRMS_CODE = ?
               AND OFFICE_CODE = ?
            """, Integer.class, officerHrmsCode.trim(), officeCode.trim());
        return count != null && count > 0;
    }
}
