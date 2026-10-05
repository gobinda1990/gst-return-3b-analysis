package gov.com.ai.webapp.service.defaulter;

import gov.com.ai.webapp.config.GstLegalConstants;
import gov.com.ai.webapp.exception.DefaulterNotFoundException;
import gov.com.ai.webapp.exception.IllegalProceedingStateException;
import gov.com.ai.webapp.model.dto.defaulter.Asmt13Response;
import gov.com.ai.webapp.model.dto.defaulter.IssueAsmt13Request;
import gov.com.ai.webapp.repository.Gstr3bFilingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;

@Slf4j
@Service
@RequiredArgsConstructor
public class Section62AssessmentService {
    private final JdbcTemplate jdbcTemplate;
    private final Gstr3bFilingRepository filingRepository;
    private final JurisdictionAuthorizationService authorizationService;
    private final NoticeReferenceGenerator referenceGenerator;
    private final DefaulterAuditService auditService;
    private final Clock clock;

    @Transactional
    public Asmt13Response issue(IssueAsmt13Request request, String officer) {
        Candidate c = loadForUpdate(request.proceedingId());
        authorizationService.requireAccess(officer, c.gstin());

        if (!c.section62Eligible()) {
            throw new IllegalProceedingStateException(
                "Proceeding is not eligible for Section 62 assessment");
        }
        if (c.asmt13RefNo() != null) {
            throw new IllegalProceedingStateException("ASMT-13 has already been issued");
        }
        if (filingRepository.hasFiled(c.gstin(), c.retPeriod())) {
            markComplied(c, officer);
            throw new IllegalProceedingStateException(
                "GSTR-3B has already been filed; ASMT-13 issuance stopped");
        }

        BigDecimal total = safe(request.assessedTax())
            .add(safe(request.assessedInterest()))
            .add(safe(request.assessedPenalty()))
            .add(safe(request.assessedLateFee()))
            .add(safe(request.assessedOther()));

        LocalDate orderDate = LocalDate.now(clock);
        LocalDate serviceDate = orderDate;
        LocalDate firstEnd = serviceDate.plusDays(
            GstLegalConstants.SECTION62_FIRST_WINDOW_DAYS);
        LocalDate extendedEnd = firstEnd.plusDays(
            GstLegalConstants.SECTION62_ADDITIONAL_WINDOW_DAYS);
        String reference = referenceGenerator.nextInternalAsmt13Reference();

        int changed = jdbcTemplate.update("""
            UPDATE GST_3B_DEFAULTER_PROCEEDING
               SET ASMT13_REF_NO = ?,
                   ASMT13_ORDER_DATE = ?,
                   ASMT13_SERVICE_DATE = ?,
                   ASMT13_STATUS = 'ISSUED',
                   ASSESSED_TAX = ?,
                   ASSESSED_INTEREST = ?,
                   ASSESSED_PENALTY = ?,
                   ASSESSED_LATE_FEE = ?,
                   ASSESSED_OTHER = ?,
                   ASSESSED_TOTAL = ?,
                   FIRST_60_DAY_END = ?,
                   EXTENDED_60_DAY_END = ?,
                   STATUS = 'ASMT13_ISSUED',
                   UPDATED_BY = ?,
                   UPDATED_AT = SYSTIMESTAMP,
                   VERSION_NO = VERSION_NO + 1
             WHERE ID = ?
               AND SECTION62_ELIGIBLE = 'Y'
               AND ASMT13_REF_NO IS NULL
            """,
            reference, Date.valueOf(orderDate), Date.valueOf(serviceDate),
            request.assessedTax(), request.assessedInterest(), request.assessedPenalty(),
            request.assessedLateFee(), request.assessedOther(), total,
            Date.valueOf(firstEnd), Date.valueOf(extendedEnd),
            officer, c.id());

        if (changed != 1) {
            throw new IllegalProceedingStateException(
                "Concurrent ASMT-13 modification detected");
        }

        auditService.record(
            c.id(), c.gstin(), c.retPeriod(), "ASMT13_ISSUED",
            c.status(), "ASMT13_ISSUED", reference, officer, request.findings());

        log.info("ASMT13 issued proceeding={} gstin={} period={} reference={}",
            c.id(), c.gstin(), c.retPeriod(), reference);

        return new Asmt13Response(
            c.id(), c.gstin(), c.retPeriod(), reference, orderDate,
            firstEnd, extendedEnd, total, "ASMT13_ISSUED");
    }

    private Candidate loadForUpdate(Long id) {
        try {
            return jdbcTemplate.queryForObject("""
                SELECT ID, GSTIN, RET_PERIOD, STATUS,
                       SECTION62_ELIGIBLE, ASMT13_REF_NO
                FROM GST_3B_DEFAULTER_PROCEEDING
                WHERE ID = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> new Candidate(
                    rs.getLong("ID"), rs.getString("GSTIN"),
                    rs.getString("RET_PERIOD"), rs.getString("STATUS"),
                    "Y".equals(rs.getString("SECTION62_ELIGIBLE")),
                    rs.getString("ASMT13_REF_NO")),
                id);
        } catch (EmptyResultDataAccessException ex) {
            throw new DefaulterNotFoundException("Proceeding not found: " + id);
        }
    }

    private void markComplied(Candidate c, String officer) {
        LocalDate filingDate = filingRepository.findFilingDate(
            c.gstin(), c.retPeriod()).orElseThrow();

        jdbcTemplate.update("""
            UPDATE GST_3B_DEFAULTER_PROCEEDING
               SET FILING_DATE = ?,
                   GSTR3A_STATUS = 'DEEMED_WITHDRAWN',
                   GSTR3A_ELIGIBLE = 'N',
                   SECTION62_ELIGIBLE = 'N',
                   STATUS = 'COMPLIED_AFTER_GSTR3A',
                   UPDATED_BY = ?,
                   UPDATED_AT = SYSTIMESTAMP,
                   VERSION_NO = VERSION_NO + 1
             WHERE ID = ?
            """, Date.valueOf(filingDate), officer, c.id());
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record Candidate(
        Long id, String gstin, String retPeriod, String status,
        boolean section62Eligible, String asmt13RefNo) {}
}
