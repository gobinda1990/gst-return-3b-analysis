package gov.com.ai.webapp.service.defaulter;

import gov.com.ai.webapp.exception.DefaulterNotFoundException;
import gov.com.ai.webapp.repository.Gstr3bFilingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class Section62ReconciliationService {
    private final JdbcTemplate jdbcTemplate;
    private final Gstr3bFilingRepository filingRepository;
    private final Section62LateFeeVerifier lateFeeVerifier;
    private final DefaulterAuditService auditService;
    private final Clock clock;

    @Transactional
    public ReconciliationResult reconcile(Long proceedingId, String officer) {
        CaseData data = loadForUpdate(proceedingId);

        if (data.serviceDate() == null) {
            return ReconciliationResult.unchanged("ASMT13_NOT_SERVED");
        }

        var filing = filingRepository.findFilingDate(data.gstin(), data.retPeriod());
        if (filing.isEmpty()) {
            return ReconciliationResult.unchanged("RETURN_NOT_FILED");
        }

        LocalDate filedOn = filing.get();
        LocalDate firstEnd = data.serviceDate().plusDays(60);
        LocalDate extendedEnd = firstEnd.plusDays(60);

        if (!filedOn.isAfter(firstEnd)) {
            withdraw(data, filedOn, officer, "FILED_WITHIN_FIRST_60_DAYS");
            return ReconciliationResult.withdrawn("FILED_WITHIN_FIRST_60_DAYS");
        }

        if (!filedOn.isAfter(extendedEnd)) {
            if (!lateFeeVerifier.additionalLateFeeSatisfied(
                    data.gstin(), data.retPeriod())) {
                return ReconciliationResult.unchanged(
                    "ADDITIONAL_LATE_FEE_NOT_VERIFIED");
            }
            withdraw(data, filedOn, officer, "FILED_WITHIN_ADDITIONAL_60_DAYS");
            return ReconciliationResult.withdrawn(
                "FILED_WITHIN_ADDITIONAL_60_DAYS");
        }

        return ReconciliationResult.unchanged("STATUTORY_WINDOW_EXPIRED");
    }

    private CaseData loadForUpdate(Long id) {
        try {
            return jdbcTemplate.queryForObject("""
                SELECT ID, GSTIN, RET_PERIOD, STATUS,
                       ASMT13_REF_NO, ASMT13_SERVICE_DATE
                FROM GST_3B_DEFAULTER_PROCEEDING
                WHERE ID = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> {
                    Date d = rs.getDate("ASMT13_SERVICE_DATE");
                    return new CaseData(
                        rs.getLong("ID"), rs.getString("GSTIN"),
                        rs.getString("RET_PERIOD"), rs.getString("STATUS"),
                        rs.getString("ASMT13_REF_NO"),
                        d == null ? null : d.toLocalDate());
                }, id);
        } catch (EmptyResultDataAccessException ex) {
            throw new DefaulterNotFoundException("Proceeding not found: " + id);
        }
    }

    private void withdraw(CaseData data, LocalDate filingDate,
            String officer, String reason) {
        LocalDate today = LocalDate.now(clock);

        jdbcTemplate.update("""
            UPDATE GST_3B_DEFAULTER_PROCEEDING
               SET FILING_DATE = ?,
                   ASMT13_STATUS = 'DEEMED_WITHDRAWN',
                   STATUS = 'ASMT13_DEEMED_WITHDRAWN',
                   ORDER_WITHDRAWN_DATE = ?,
                   UPDATED_BY = ?,
                   UPDATED_AT = SYSTIMESTAMP,
                   VERSION_NO = VERSION_NO + 1
             WHERE ID = ?
            """, Date.valueOf(filingDate), Date.valueOf(today), officer, data.id());

        auditService.record(
            data.id(), data.gstin(), data.retPeriod(), "ASMT13_DEEMED_WITHDRAWN",
            data.status(), "ASMT13_DEEMED_WITHDRAWN",
            data.asmt13RefNo(), officer, reason);
    }

    private record CaseData(
        Long id, String gstin, String retPeriod, String status,
        String asmt13RefNo, LocalDate serviceDate) {}

    public record ReconciliationResult(boolean withdrawn, String reason) {
        public static ReconciliationResult withdrawn(String reason) {
            return new ReconciliationResult(true, reason);
        }
        public static ReconciliationResult unchanged(String reason) {
            return new ReconciliationResult(false, reason);
        }
    }
}
