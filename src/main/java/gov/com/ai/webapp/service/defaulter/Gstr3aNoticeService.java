package gov.com.ai.webapp.service.defaulter;

import gov.com.ai.webapp.config.GstLegalConstants;
import gov.com.ai.webapp.exception.IllegalProceedingStateException;
import gov.com.ai.webapp.model.dto.defaulter.Gstr3aResponse;
import gov.com.ai.webapp.model.dto.defaulter.IssueGstr3aRequest;
import gov.com.ai.webapp.repository.Gstr3bFilingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class Gstr3aNoticeService {

	private static final String RETURN_TYPE = "GSTR-3B";
	private static final String STATUS_GSTR3A_ELIGIBLE = "GSTR3A_ELIGIBLE";
	private static final String STATUS_GSTR3A_PENDING = "GSTR3A_COMPLIANCE_PENDING";
	private static final int MAX_REMARKS_LENGTH = 1000;

	private static final Pattern GSTIN_PATTERN = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][A-Z0-9]Z[A-Z0-9]$");
	private static final Pattern PERIOD_PATTERN = Pattern.compile("^(0[1-9]|1[0-2])\\d{4}$");

	private final JdbcTemplate jdbcTemplate;
	private final Gstr3aEligibilityService eligibilityService;
	private final Gstr3bFilingRepository filingRepository;
	private final JurisdictionAuthorizationService authorizationService;
	private final NoticeReferenceGenerator referenceGenerator;
	private final DefaulterAuditService auditService;
	private final Clock clock;

	/**
	 * Issues a GSTR-3A notice.
	 *
	 * @param request  validated request
	 * @param hrmsCode HRMS code of the authenticated officer (audit trail)
	 * @param officeIds office IDs assigned to the officer (jurisdiction check)
	 */
	@Transactional
	public Gstr3aResponse issue(IssueGstr3aRequest request, String hrmsCode, List<String> officeIds) {

		validateRequest(request);

		final String officerCode = normalizeOfficer(hrmsCode);
		final String gstin = normalizeGstin(request.gstin());
		final String period = normalizePeriod(request.retPeriod());

		log.info("Starting GSTR-3A issuance gstin={} period={} officer={}", gstin, period, officerCode);

		// STEP 1: officer must be assigned to the taxpayer's office.
		var jurisdiction = authorizationService.requireAccess(officeIds, gstin);

		// STEP 2: statutory eligibility.
		var eligibility = eligibilityService.evaluate(gstin, period);

		if (!eligibility.eligible()) {
			log.warn("GSTR-3A eligibility rejected gstin={} period={} reason={}", gstin, period,
					eligibility.reason());
			throw new IllegalProceedingStateException("GSTR-3A cannot be issued: " + eligibility.reason());
		}

		// STEP 3: create the proceeding if absent (nullable params bound with explicit
		// JDBC types to avoid ORA-17004).
		Long proceedingId = createOrGet(gstin, period, eligibility, jurisdiction.stJuri(),
				jurisdiction.officeCode(), officerCode);

		// STEP 4: serialize mutation on this proceeding and read current issuance state.
		boolean alreadyIssued = lockAndCheckIssued(proceedingId);

		// STEP 5: authoritative filing recheck AFTER the row lock. No update-and-throw
		// here: an unchecked exception would roll the update back. Reconciliation is
		// handled by the dedicated workflow.
		if (filingRepository.hasFiled(gstin, period)) {
			log.info("GSTR-3B already filed during GSTR-3A recheck proceeding={} gstin={} period={}", proceedingId,
					gstin, period);
			throw new IllegalProceedingStateException("GSTR-3B has already been filed. "
					+ "Refresh/reconcile the proceeding before performing any statutory action.");
		}

		// STEP 6: idempotency / duplicate protection.
		if (alreadyIssued) {
			throw new IllegalProceedingStateException("GSTR-3A has already been issued");
		}

		LocalDate issueDate = LocalDate.now(clock);

		// Issuance and service are assumed to occur on the same date. If electronic
		// service happens later, populate service date and deadline from the
		// notice-service acknowledgement flow instead.
		LocalDate serviceDate = issueDate;
		LocalDate deadline = serviceDate.plusDays(GstLegalConstants.GSTR3A_COMPLIANCE_DAYS);

		String reference = referenceGenerator.nextInternalGstr3aReference();

		// STEP 7: conditional update as a second concurrency barrier.
		int updated = jdbcTemplate.update("""
				UPDATE GST_3B_DEFAULTER_PROCEEDING
				   SET GSTR3A_ELIGIBLE     = 'Y',
				       GSTR3A_REF_NO        = ?,
				       GSTR3A_ISSUE_DATE    = ?,
				       GSTR3A_SERVICE_DATE  = ?,
				       GSTR3A_DEADLINE      = ?,
				       GSTR3A_STATUS        = 'ISSUED',
				       STATUS               = ?,
				       UPDATED_BY           = ?,
				       UPDATED_AT           = SYSTIMESTAMP,
				       VERSION_NO           = NVL(VERSION_NO, 0) + 1
				 WHERE ID = ?
				   AND GSTR3A_REF_NO IS NULL
				   AND FILING_DATE IS NULL
				""", reference, Date.valueOf(issueDate), Date.valueOf(serviceDate), Date.valueOf(deadline),
				STATUS_GSTR3A_PENDING, officerCode, proceedingId);

		if (updated != 1) {
			log.warn("Concurrent/stale GSTR-3A issuance proceeding={} gstin={} period={}", proceedingId, gstin,
					period);
			throw new IllegalProceedingStateException("GSTR-3A issuance could not be completed because "
					+ "the proceeding state changed. Refresh and retry.");
		}

		// STEP 8: immutable statutory audit.
		auditService.record(proceedingId, gstin, period, "GSTR3A_ISSUED", STATUS_GSTR3A_ELIGIBLE,
				STATUS_GSTR3A_PENDING, reference, officerCode, sanitizeRemarks(request.remarks()));

		log.info("GSTR-3A issued proceeding={} gstin={} period={} reference={} deadline={}", proceedingId, gstin,
				period, reference, deadline);

		return new Gstr3aResponse(proceedingId, gstin, period, STATUS_GSTR3A_PENDING, reference, issueDate, deadline,
				"Section 46 read with Rule 68");
	}

	/* ----------------------------- CREATE / GET ----------------------------- */

	private Long createOrGet(String gstin, String period, Gstr3aEligibilityService.EligibilityResult eligibility,
			String stJuri, String officeCode, String officerCode) {

		Objects.requireNonNull(eligibility, "eligibility must not be null");

		final String sql = """
				MERGE INTO GST_3B_DEFAULTER_PROCEEDING T
				USING (
				    SELECT ? AS GSTIN,
				           ? AS RET_PERIOD
				      FROM DUAL
				) S
				   ON (
				       T.GSTIN = S.GSTIN
				       AND T.RET_PERIOD = S.RET_PERIOD
				       AND T.RETURN_TYPE = 'GSTR-3B'
				   )
				WHEN NOT MATCHED THEN
				INSERT (
				    ID, GSTIN, RET_PERIOD, RETURN_TYPE, FILING_FREQUENCY, TAXPAYER_CATEGORY,
				    DUE_DATE, STATUS, GSTR3A_ELIGIBLE, SECTION62_ELIGIBLE, ST_JURI, OFFICE_CODE,
				    CREATED_BY, CREATED_AT, VERSION_NO
				)
				VALUES (
				    SEQ_GST3B_DEF_PROC.NEXTVAL, S.GSTIN, S.RET_PERIOD, 'GSTR-3B', ?, ?, ?,
				    'GSTR3A_ELIGIBLE', 'Y', 'N', ?, ?, ?, SYSTIMESTAMP, 0
				)
				""";

		// Do NOT replace with jdbcTemplate.update(sql, Object...): generic null
		// inference with Oracle JDBC can raise ORA-17004 (invalid column type).
		int affected = jdbcTemplate.update(connection -> {

			PreparedStatement ps = connection.prepareStatement(sql);
			int index = 1;

			ps.setString(index++, gstin);
			ps.setString(index++, period);
			setNullableVarchar(ps, index++, eligibility.frequency());
			setNullableVarchar(ps, index++, eligibility.category());
			setNullableDate(ps, index++, eligibility.dueDate());
			setNullableVarchar(ps, index++, stJuri);
			setNullableVarchar(ps, index++, officeCode);
			ps.setString(index, officerCode);

			return ps;
		});

		log.debug("Proceeding MERGE completed gstin={} period={} affected={}", gstin, period, affected);

		Long proceedingId = jdbcTemplate.query("""
				SELECT ID
				  FROM GST_3B_DEFAULTER_PROCEEDING
				 WHERE GSTIN = ?
				   AND RET_PERIOD = ?
				   AND RETURN_TYPE = ?
				""", rs -> rs.next() ? rs.getLong(1) : null, gstin, period, RETURN_TYPE);

		if (proceedingId == null) {
			throw new IllegalStateException("Unable to resolve GSTR-3B proceeding after MERGE");
		}

		return proceedingId;
	}

	/* --------------------------- LOCK + ISSUED CHECK ------------------------- */

	/**
	 * Takes a row lock on the proceeding and returns whether GSTR-3A has already
	 * been issued. Single round trip instead of separate lock and count queries.
	 */
	private boolean lockAndCheckIssued(Long id) {

		Boolean issued = jdbcTemplate.query("""
				SELECT GSTR3A_REF_NO
				  FROM GST_3B_DEFAULTER_PROCEEDING
				 WHERE ID = ?
				 FOR UPDATE
				""", rs -> rs.next() ? rs.getString(1) != null : null, id);

		if (issued == null) {
			throw new IllegalProceedingStateException("Proceeding does not exist: " + id);
		}

		return issued;
	}

	/* ----------------------------- JDBC NULL BINDING ------------------------- */

	private static void setNullableVarchar(PreparedStatement ps, int index, String value) throws SQLException {
		if (value == null || value.isBlank()) {
			ps.setNull(index, Types.VARCHAR);
		} else {
			ps.setString(index, value.trim());
		}
	}

	private static void setNullableDate(PreparedStatement ps, int index, LocalDate value) throws SQLException {
		if (value == null) {
			ps.setNull(index, Types.DATE);
		} else {
			ps.setDate(index, Date.valueOf(value));
		}
	}

	/* ------------------------ VALIDATION / NORMALIZATION --------------------- */

	private static void validateRequest(IssueGstr3aRequest request) {

		if (request == null) {
			throw new IllegalArgumentException("GSTR-3A request is required");
		}
		if (request.gstin() == null || request.gstin().isBlank()) {
			throw new IllegalArgumentException("GSTIN is required");
		}
		if (request.retPeriod() == null || request.retPeriod().isBlank()) {
			throw new IllegalArgumentException("Return period is required");
		}
	}

	private static String normalizeGstin(String gstin) {

		String value = gstin.trim().toUpperCase(Locale.ROOT);

		if (!GSTIN_PATTERN.matcher(value).matches()) {
			throw new IllegalArgumentException("Invalid GSTIN: " + value);
		}
		return value;
	}

	private static String normalizePeriod(String period) {

		String value = period.trim();

		if (!PERIOD_PATTERN.matcher(value).matches()) {
			throw new IllegalArgumentException("Invalid return period. Expected MMYYYY.");
		}
		return value;
	}

	private static String normalizeOfficer(String officer) {

		if (officer == null || officer.isBlank()) {
			throw new IllegalArgumentException("Authenticated officer HRMS code is required");
		}
		return officer.trim();
	}

	private static String sanitizeRemarks(String remarks) {

		if (remarks == null) {
			return null;
		}

		String value = remarks.trim();

		if (value.isEmpty()) {
			return null;
		}

		return value.length() > MAX_REMARKS_LENGTH ? value.substring(0, MAX_REMARKS_LENGTH) : value;
	}
}