package gov.com.ai.webapp.service.defaulter;

import gov.com.ai.webapp.config.DefaulterProperties;
import gov.com.ai.webapp.repository.Gstr3bFilingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class Section62EligibilityService {
    private final JdbcTemplate jdbcTemplate;
    private final Gstr3bFilingRepository filingRepository;
    private final DefaulterAuditService auditService;
    private final DefaulterProperties properties;
    private final Clock clock;

    @Transactional
    public ScanResult scan() {
        int limit = Math.min(Math.max(properties.getScanBatchSize(), 1),
                             properties.getMaxBatchSize());
        LocalDate today = LocalDate.now(clock);

        List<Candidate> candidates = jdbcTemplate.query("""
            SELECT * FROM (
                SELECT ID, GSTIN, RET_PERIOD, STATUS
                FROM GST_3B_DEFAULTER_PROCEEDING
                WHERE GSTR3A_STATUS IN ('ISSUED','SERVED')
                  AND GSTR3A_DEADLINE < ?
                  AND SECTION62_ELIGIBLE = 'N'
                  AND ASMT13_REF_NO IS NULL
                ORDER BY ID
            )
            WHERE ROWNUM <= ?
            """,
            (rs, rowNum) -> new Candidate(
                rs.getLong("ID"), rs.getString("GSTIN"),
                rs.getString("RET_PERIOD"), rs.getString("STATUS")),
            Date.valueOf(today), limit);

        int eligible = 0;
        int complied = 0;

        for (Candidate c : candidates) {
            if (filingRepository.hasFiled(c.gstin(), c.retPeriod())) {
                markComplied(c);
                complied++;
                continue;
            }

            int changed = jdbcTemplate.update("""
                UPDATE GST_3B_DEFAULTER_PROCEEDING
                   SET GSTR3A_STATUS = 'EXPIRED',
                       SECTION62_ELIGIBLE = 'Y',
                       SECTION62_ELIGIBLE_DATE = ?,
                       STATUS = 'SECTION62_ELIGIBLE',
                       UPDATED_AT = SYSTIMESTAMP,
                       VERSION_NO = VERSION_NO + 1
                 WHERE ID = ?
                   AND SECTION62_ELIGIBLE = 'N'
                   AND ASMT13_REF_NO IS NULL
                """, Date.valueOf(today), c.id());

            if (changed == 1) {
                eligible++;
                auditService.record(
                    c.id(), c.gstin(), c.retPeriod(), "SECTION62_ELIGIBLE",
                    c.status(), "SECTION62_ELIGIBLE", null, "SYSTEM",
                    "GSTR-3A compliance period expired; return remains unfiled");
            }
        }

        log.info("Section62 scan scanned={} eligible={} complied={}",
            candidates.size(), eligible, complied);
        return new ScanResult(candidates.size(), eligible, complied);
    }

    private void markComplied(Candidate c) {
        LocalDate filingDate = filingRepository.findFilingDate(
            c.gstin(), c.retPeriod()).orElseThrow();

        jdbcTemplate.update("""
            UPDATE GST_3B_DEFAULTER_PROCEEDING
               SET FILING_DATE = ?,
                   GSTR3A_STATUS = 'DEEMED_WITHDRAWN',
                   GSTR3A_ELIGIBLE = 'N',
                   SECTION62_ELIGIBLE = 'N',
                   STATUS = 'COMPLIED_AFTER_GSTR3A',
                   UPDATED_AT = SYSTIMESTAMP,
                   VERSION_NO = VERSION_NO + 1
             WHERE ID = ?
            """, Date.valueOf(filingDate), c.id());

        auditService.record(
            c.id(), c.gstin(), c.retPeriod(), "GSTR3A_DEEMED_WITHDRAWN",
            c.status(), "COMPLIED_AFTER_GSTR3A", null, "SYSTEM",
            "Return filed before assessment order");
    }

    private record Candidate(Long id, String gstin, String retPeriod, String status) {}
    public record ScanResult(int scanned, int section62Eligible, int complied) {}
}
