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
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class Gstr3aNoticeService {

	private static final String RETURN_TYPE = "GSTR-3B";

	private static final String STATUS_GSTR3A_ELIGIBLE = "GSTR3A_ELIGIBLE";

	private static final String STATUS_GSTR3A_PENDING = "GSTR3A_COMPLIANCE_PENDING";

	private final JdbcTemplate jdbcTemplate;
	private final Gstr3aEligibilityService eligibilityService;
	private final Gstr3bFilingRepository filingRepository;
	private final JurisdictionAuthorizationService authorizationService;
	private final NoticeReferenceGenerator referenceGenerator;
	private final DefaulterAuditService auditService;
	private final Clock clock;

	/*
	 * ========================================================= ISSUE GSTR-3A
	 * =========================================================
	 */

	@Transactional
	public Gstr3aResponse issue(IssueGstr3aRequest request, String officer) {

		validateRequest(request);

		String gstin = normalizeGstin(request.gstin());
		String period = normalizePeriod(request.retPeriod());
		String officerCode = normalizeOfficer(officer);

		log.info("Starting GSTR-3A issuance gstin={} period={} officer={}", gstin, period, officerCode);

		/*
		 * STEP 1 Authorize officer against taxpayer jurisdiction.
		 */
		var jurisdiction = authorizationService.requireAccess(officerCode, gstin);

		/*
		 * STEP 2 Calculate statutory eligibility.
		 */
		var eligibility = eligibilityService.evaluate(gstin, period);

		if (!eligibility.eligible()) {

			log.warn("GSTR-3A eligibility rejected gstin={} period={} reason={}", gstin, period, eligibility.reason());

			throw new IllegalProceedingStateException("GSTR-3A cannot be issued: " + eligibility.reason());
		}

		/*
		 * STEP 3 Create proceeding if it does not already exist.
		 *
		 * Important: Nullable Oracle MERGE parameters are bound using explicit JDBC
		 * types to prevent ORA-17004.
		 */
		Long proceedingId = createOrGet(gstin, period, eligibility, jurisdiction.stJuri(), jurisdiction.officeCode(),
				officerCode);

		/*
		 * STEP 4 Serialize statutory mutation for this proceeding.
		 */
		lock(proceedingId);

		/*
		 * STEP 5 Authoritative filing recheck AFTER row lock.
		 *
		 * Do not issue GSTR-3A when the return has already been filed.
		 */
		if (filingRepository.hasFiled(gstin, period)) {

			log.info("GSTR-3B already filed during GSTR-3A recheck " + "proceeding={} gstin={} period={}", proceedingId,
					gstin, period);

			/*
			 * We deliberately do not update-and-throw here.
			 *
			 * issue() is transactional and an unchecked exception would roll the update
			 * back.
			 *
			 * Filing reconciliation should be performed by the dedicated reconciliation
			 * workflow.
			 */
			throw new IllegalProceedingStateException("GSTR-3B has already been filed. "
					+ "Refresh/reconcile the proceeding before " + "performing any statutory action.");
		}

		/*
		 * STEP 6 Idempotency / duplicate protection.
		 */
		if (alreadyIssued(proceedingId)) {

			throw new IllegalProceedingStateException("GSTR-3A has already been issued");
		}

		LocalDate issueDate = LocalDate.now(clock);

		/*
		 * IMPORTANT:
		 *
		 * If actual electronic service can occur after generation, GSTR3A_SERVICE_DATE
		 * and GSTR3A_DEADLINE should instead be populated by the notice-service
		 * acknowledgement flow.
		 *
		 * Current implementation assumes issuance and service occur on the same date.
		 */
		LocalDate serviceDate = issueDate;

		LocalDate deadline = serviceDate.plusDays(GstLegalConstants.GSTR3A_COMPLIANCE_DAYS);

		String reference = referenceGenerator.nextInternalGstr3aReference();

		/*
		 * STEP 7 Conditional update gives another concurrency barrier.
		 */
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

			log.warn("Concurrent/stale GSTR-3A issuance " + "proceeding={} gstin={} period={}", proceedingId, gstin,
					period);

			throw new IllegalProceedingStateException("GSTR-3A issuance could not be completed because "
					+ "the proceeding state changed. Refresh and retry.");
		}

		/*
		 * STEP 8 Immutable statutory audit.
		 */
		auditService.record(proceedingId, gstin, period, "GSTR3A_ISSUED", STATUS_GSTR3A_ELIGIBLE, STATUS_GSTR3A_PENDING,
				reference, officerCode, sanitizeRemarks(request.remarks()));

		log.info("GSTR-3A issued proceeding={} gstin={} " + "period={} reference={} deadline={}", proceedingId, gstin,
				period, reference, deadline);

		return new Gstr3aResponse(proceedingId, gstin, period, STATUS_GSTR3A_PENDING, reference, issueDate, deadline,
				"Section 46 read with Rule 68");
	}

	/*
	 * ========================================================= CREATE / GET
	 * PROCEEDING =========================================================
	 */

	private Long createOrGet(String gstin, String period, Gstr3aEligibilityService.EligibilityResult eligibility,
			String stJuri, String officeCode, String officer) {

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
				    ID,
				    GSTIN,
				    RET_PERIOD,
				    RETURN_TYPE,
				    FILING_FREQUENCY,
				    TAXPAYER_CATEGORY,
				    DUE_DATE,
				    STATUS,
				    GSTR3A_ELIGIBLE,
				    SECTION62_ELIGIBLE,
				    ST_JURI,
				    OFFICE_CODE,
				    CREATED_BY,
				    CREATED_AT,
				    VERSION_NO
				)

				VALUES (
				    SEQ_GST3B_DEF_PROC.NEXTVAL,
				    S.GSTIN,
				    S.RET_PERIOD,
				    'GSTR-3B',
				    ?,
				    ?,
				    ?,
				    'GSTR3A_ELIGIBLE',
				    'Y',
				    'N',
				    ?,
				    ?,
				    ?,
				    SYSTIMESTAMP,
				    0
				)
				""";

		/*
		 * DO NOT replace this with:
		 *
		 * jdbcTemplate.update(sql, Object...)
		 *
		 * Some of the parameters are nullable. With Oracle JDBC, generic null inference
		 * can result in:
		 *
		 * ORA-17004: Invalid column type: 268435455
		 *
		 * Therefore every nullable parameter is explicitly typed.
		 */
		int affected = jdbcTemplate.update(connection -> {

			PreparedStatement ps = connection.prepareStatement(sql);

			int index = 1;

			/* 1 - GSTIN */
			ps.setString(index++, gstin);

			/* 2 - RET_PERIOD */
			ps.setString(index++, period);

			/* 3 - FILING_FREQUENCY */
			setNullableVarchar(ps, index++, eligibility.frequency());

			/* 4 - TAXPAYER_CATEGORY */
			setNullableVarchar(ps, index++, eligibility.category());

			/* 5 - DUE_DATE */
			setNullableDate(ps, index++, eligibility.dueDate());

			/* 6 - ST_JURI */
			setNullableVarchar(ps, index++, stJuri);

			/* 7 - OFFICE_CODE */
			setNullableVarchar(ps, index++, officeCode);

			/* 8 - CREATED_BY */
			ps.setString(index, officer);

			return ps;
		});

		log.debug("Proceeding MERGE completed gstin={} period={} affected={}", gstin, period, affected);

		Long proceedingId = jdbcTemplate.queryForObject("""
				SELECT ID
				  FROM GST_3B_DEFAULTER_PROCEEDING
				 WHERE GSTIN = ?
				   AND RET_PERIOD = ?
				   AND RETURN_TYPE = ?
				""", Long.class, gstin, period, RETURN_TYPE);

		if (proceedingId == null) {

			throw new IllegalStateException("Unable to resolve GSTR-3B proceeding after MERGE");
		}

		return proceedingId;
	}

	/*
	 * ========================================================= ROW LOCK
	 * =========================================================
	 */

	private void lock(Long id) {

		Long lockedId = jdbcTemplate.queryForObject("""
				SELECT ID
				  FROM GST_3B_DEFAULTER_PROCEEDING
				 WHERE ID = ?
				 FOR UPDATE
				""", Long.class, id);

		if (lockedId == null) {

			throw new IllegalProceedingStateException("Proceeding does not exist: " + id);
		}
	}

	/*
	 * ========================================================= ALREADY ISSUED
	 * =========================================================
	 */

	private boolean alreadyIssued(Long id) {

		Integer count = jdbcTemplate.queryForObject("""
				SELECT COUNT(1)
				  FROM GST_3B_DEFAULTER_PROCEEDING
				 WHERE ID = ?
				   AND GSTR3A_REF_NO IS NOT NULL
				""", Integer.class, id);

		return count != null && count > 0;
	}

	/*
	 * ========================================================= JDBC NULL BINDING
	 * =========================================================
	 */

	private static void setNullableVarchar(PreparedStatement ps, int index, String value) throws java.sql.SQLException {

		if (value == null || value.isBlank()) {

			ps.setNull(index, Types.VARCHAR);

		} else {

			ps.setString(index, value.trim());
		}
	}

	private static void setNullableDate(PreparedStatement ps, int index, LocalDate value) throws java.sql.SQLException {

		if (value == null) {

			ps.setNull(index, Types.DATE);

		} else {

			ps.setDate(index, Date.valueOf(value));
		}
	}

	/*
	 * ========================================================= VALIDATION /
	 * NORMALIZATION =========================================================
	 */

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

		if (!value.matches("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][A-Z0-9]Z[A-Z0-9]$")) {

			throw new IllegalArgumentException("Invalid GSTIN: " + value);
		}

		return value;
	}

	private static String normalizePeriod(String period) {

		String value = period.trim();

		if (!value.matches("^(0[1-9]|1[0-2])\\d{4}$")) {

			throw new IllegalArgumentException("Invalid return period. Expected MMYYYY.");
		}

		return value;
	}

	private static String normalizeOfficer(String officer) {

		if (officer == null || officer.isBlank()) {

			//throw new IllegalArgumentException("Authenticated officer HRMS code is required");
			return "WB001";
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

		/*
		 * Defensive service-side limit even when DTO validation is already present.
		 */
		if (value.length() > 1000) {
			return value.substring(0, 1000);
		}

		return value;
	}
}