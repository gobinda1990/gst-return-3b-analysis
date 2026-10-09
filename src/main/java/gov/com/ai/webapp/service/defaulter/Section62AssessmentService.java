package gov.com.ai.webapp.service.defaulter;

import gov.com.ai.webapp.config.GstLegalConstants;
import gov.com.ai.webapp.exception.DefaulterNotFoundException;
import gov.com.ai.webapp.exception.IllegalProceedingStateException;
import gov.com.ai.webapp.model.dto.defaulter.Asmt13Response;
import gov.com.ai.webapp.model.dto.defaulter.IssueAsmt13Request;
import gov.com.ai.webapp.repository.Gstr3bFilingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

@Slf4j
@Service
@RequiredArgsConstructor
public class Section62AssessmentService {

	private static final int MAX_FINDINGS_LENGTH = 4000;

	private final JdbcTemplate jdbcTemplate;
	private final Gstr3bFilingRepository filingRepository;
	private final JurisdictionAuthorizationService authorizationService;
	private final NoticeReferenceGenerator referenceGenerator;
	private final DefaulterAuditService auditService;
	private final Clock clock;

	/**
	 * Issues ASMT-13.
	 *
	 * noRollbackFor: when the return is found to be filed, the proceeding is marked
	 * complied and the exception is raised to stop issuance. That state change must
	 * be committed, so this exception type must not roll the transaction back. Every
	 * other throw in this method happens before any write.
	 *
	 * @param hrmsCode  authenticated officer's HRMS code (audit trail)
	 * @param officeIds office IDs assigned to the officer (jurisdiction check)
	 */
	@Transactional(noRollbackFor = IllegalProceedingStateException.class)
	public Asmt13Response issue(IssueAsmt13Request request, String hrmsCode, List<String> officeIds) {

		if (request == null || request.proceedingId() == null) {
			throw new IllegalArgumentException("Proceeding id is required");
		}
		if (hrmsCode == null || hrmsCode.isBlank()) {
			throw new IllegalArgumentException("Authenticated officer HRMS code is required");
		}
		final String officer = hrmsCode.trim();

		BigDecimal tax = amount(request.assessedTax(), "assessedTax");
		BigDecimal interest = amount(request.assessedInterest(), "assessedInterest");
		BigDecimal penalty = amount(request.assessedPenalty(), "assessedPenalty");
		BigDecimal lateFee = amount(request.assessedLateFee(), "assessedLateFee");
		BigDecimal other = amount(request.assessedOther(), "assessedOther");

		BigDecimal total = tax.add(interest).add(penalty).add(lateFee).add(other);

		if (total.signum() <= 0) {
			throw new IllegalArgumentException("Total assessed amount must be greater than zero");
		}

		Candidate c = loadForUpdate(request.proceedingId());

		authorizationService.requireAccess(officeIds, c.gstin());

		if (!c.section62Eligible()) {
			throw new IllegalProceedingStateException("Proceeding is not eligible for Section 62 assessment");
		}
		if (c.asmt13RefNo() != null) {
			throw new IllegalProceedingStateException("ASMT-13 has already been issued");
		}
		if (filingRepository.hasFiled(c.gstin(), c.retPeriod())) {
			markComplied(c, officer);
			throw new IllegalProceedingStateException("GSTR-3B has already been filed; ASMT-13 issuance stopped");
		}

		LocalDate orderDate = LocalDate.now(clock);
		LocalDate serviceDate = orderDate;
		LocalDate firstEnd = serviceDate.plusDays(GstLegalConstants.SECTION62_FIRST_WINDOW_DAYS);
		LocalDate extendedEnd = firstEnd.plusDays(GstLegalConstants.SECTION62_ADDITIONAL_WINDOW_DAYS);
		String reference = referenceGenerator.nextInternalAsmt13Reference();

		// All amounts are non-null here, so no ORA-17004 null-binding risk.
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
				       VERSION_NO = NVL(VERSION_NO, 0) + 1
				 WHERE ID = ?
				   AND SECTION62_ELIGIBLE = 'Y'
				   AND ASMT13_REF_NO IS NULL
				   AND FILING_DATE IS NULL
				""", reference, Date.valueOf(orderDate), Date.valueOf(serviceDate), tax, interest, penalty, lateFee,
				other, total, Date.valueOf(firstEnd), Date.valueOf(extendedEnd), officer, c.id());

		if (changed != 1) {
			throw new IllegalProceedingStateException("Concurrent ASMT-13 modification detected");
		}

		auditService.record(c.id(), c.gstin(), c.retPeriod(), "ASMT13_ISSUED", c.status(), "ASMT13_ISSUED",
				reference, officer, sanitize(request.findings()));

		log.info("ASMT13 issued proceeding={} gstin={} period={} reference={} officer={}", c.id(), c.gstin(),
				c.retPeriod(), reference, officer);

		return new Asmt13Response(c.id(), c.gstin(), c.retPeriod(), reference, orderDate, firstEnd, extendedEnd,
				total, "ASMT13_ISSUED");
	}

	/* ------------------------------- INTERNALS ------------------------------ */

	private Candidate loadForUpdate(Long id) {

		return jdbcTemplate.query("""
				SELECT ID, GSTIN, RET_PERIOD, STATUS, SECTION62_ELIGIBLE, ASMT13_REF_NO
				  FROM GST_3B_DEFAULTER_PROCEEDING
				 WHERE ID = ?
				 FOR UPDATE
				""", rs -> {
			if (!rs.next()) {
				throw new DefaulterNotFoundException("Proceeding not found: " + id);
			}
			return new Candidate(rs.getLong("ID"), rs.getString("GSTIN"), rs.getString("RET_PERIOD"),
					rs.getString("STATUS"), "Y".equals(rs.getString("SECTION62_ELIGIBLE")),
					rs.getString("ASMT13_REF_NO"));
		}, id);
	}

	private void markComplied(Candidate c, String officer) {

		LocalDate filingDate = filingRepository.findFilingDate(c.gstin(), c.retPeriod())
				.orElseThrow(() -> new NoSuchElementException(
						"Filing date not found for gstin=" + c.gstin() + " period=" + c.retPeriod()));

		int updated = jdbcTemplate.update("""
				UPDATE GST_3B_DEFAULTER_PROCEEDING
				   SET FILING_DATE = ?,
				       GSTR3A_STATUS = 'DEEMED_WITHDRAWN',
				       GSTR3A_ELIGIBLE = 'N',
				       SECTION62_ELIGIBLE = 'N',
				       STATUS = 'COMPLIED_AFTER_GSTR3A',
				       UPDATED_BY = ?,
				       UPDATED_AT = SYSTIMESTAMP,
				       VERSION_NO = NVL(VERSION_NO, 0) + 1
				 WHERE ID = ?
				   AND FILING_DATE IS NULL
				""", Date.valueOf(filingDate), officer, c.id());

		if (updated == 1) {
			auditService.record(c.id(), c.gstin(), c.retPeriod(), "COMPLIED_AFTER_GSTR3A", c.status(),
					"COMPLIED_AFTER_GSTR3A", null, officer, "GSTR-3B filed before ASMT-13 issuance");
		}
	}

	private static BigDecimal amount(BigDecimal value, String field) {

		if (value == null) {
			return BigDecimal.ZERO;
		}
		if (value.signum() < 0) {
			throw new IllegalArgumentException(field + " must not be negative");
		}
		return value;
	}

	private static String sanitize(String text) {

		if (text == null) {
			return null;
		}

		String value = text.trim();

		if (value.isEmpty()) {
			return null;
		}

		return value.length() > MAX_FINDINGS_LENGTH ? value.substring(0, MAX_FINDINGS_LENGTH) : value;
	}

	private record Candidate(Long id, String gstin, String retPeriod, String status, boolean section62Eligible,
			String asmt13RefNo) {
	}
}