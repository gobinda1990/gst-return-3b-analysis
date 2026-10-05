package gov.com.ai.webapp.repository;

import gov.com.ai.webapp.model.dto.defaulter.ProceedingAuditResponse;
import gov.com.ai.webapp.model.dto.defaulter.ProceedingRowResponse;
import gov.com.ai.webapp.model.dto.defaulter.ProceedingSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class DefaulterProceedingQueryRepository {

	private final NamedParameterJdbcTemplate jdbc;

	public ProceedingSummaryResponse summary(String retPeriod, String status, String search) {

		StringBuilder sql = new StringBuilder("""
				SELECT
				    COUNT(*) AS TOTAL,
				    NVL(SUM(CASE
				        WHEN P.STATUS NOT IN (
				            'FILED_ON_TIME',
				            'COMPLIED_AFTER_GSTR3A',
				            'ASMT13_DEEMED_WITHDRAWN',
				            'ASMT13_FINAL'
				        ) THEN 1 ELSE 0 END), 0) AS OPEN_PROCEEDINGS,
				    NVL(SUM(CASE
				        WHEN P.STATUS = 'GSTR3A_ELIGIBLE'
				        THEN 1 ELSE 0 END), 0) AS GSTR3A_ELIGIBLE,
				    NVL(SUM(CASE
				        WHEN P.STATUS = 'GSTR3A_COMPLIANCE_PENDING'
				        THEN 1 ELSE 0 END), 0) AS GSTR3A_PENDING,
				    NVL(SUM(CASE
				        WHEN P.STATUS = 'SECTION62_ELIGIBLE'
				        THEN 1 ELSE 0 END), 0) AS SECTION62_ELIGIBLE,
				    NVL(SUM(CASE
				        WHEN P.STATUS = 'ASMT13_ISSUED'
				        THEN 1 ELSE 0 END), 0) AS ASMT13_ISSUED
				FROM GST_3B_DEFAULTER_PROCEEDING P
				WHERE 1 = 1
				""");

		MapSqlParameterSource params = new MapSqlParameterSource();
		appendFilters(sql, params, retPeriod, status, search);

		return jdbc.queryForObject(sql.toString(), params,
				(rs, rowNum) -> new ProceedingSummaryResponse(rs.getLong("TOTAL"), rs.getLong("OPEN_PROCEEDINGS"),
						rs.getLong("GSTR3A_ELIGIBLE"), rs.getLong("GSTR3A_PENDING"), rs.getLong("SECTION62_ELIGIBLE"),
						rs.getLong("ASMT13_ISSUED")));
	}

	public long count(String retPeriod, String status, String search) {

		StringBuilder sql = new StringBuilder("""
				SELECT COUNT(*)
				FROM GST_3B_DEFAULTER_PROCEEDING P
				WHERE 1 = 1
				""");

		MapSqlParameterSource params = new MapSqlParameterSource();
		appendFilters(sql, params, retPeriod, status, search);

		Long value = jdbc.queryForObject(sql.toString(), params, Long.class);
		return value == null ? 0L : value;
	}

	public List<ProceedingRowResponse> findPage(String retPeriod, String status, String search, int page, int size) {

		long startRow = (long) page * size;
		long endRow = startRow + size;

		StringBuilder filter = new StringBuilder();
		MapSqlParameterSource params = new MapSqlParameterSource();
		appendFilters(filter, params, retPeriod, status, search);

		params.addValue("startRow", startRow);
		params.addValue("endRow", endRow);

		/*
		 * Oracle 11g compatible pagination. Do not use OFFSET/FETCH because that
		 * requires Oracle 12c+.
		 *
		 * OFFICE_NAME is intentionally returned as NULL here. The physical MASTER_JURI
		 * column names differ by installation. ST_JURI and OFFICE_CODE are persisted on
		 * the proceeding and remain authoritative. Replace NULL OFFICE_NAME with your
		 * verified MASTER_JURI join when the exact DDL is available.
		 */
		String sql = """
				SELECT *
				FROM (
				    SELECT X.*,
				           ROW_NUMBER() OVER (
				               ORDER BY X.ID DESC
				           ) AS RN
				    FROM (
				        SELECT
				            P.ID,
				            P.GSTIN,
				            P.RET_PERIOD,
				            P.RETURN_TYPE,
				            P.FILING_FREQUENCY,
				            P.TAXPAYER_CATEGORY,
				            P.DUE_DATE,
				            P.FILING_DATE,
				            P.STATUS,
				            P.GSTR3A_ELIGIBLE,
				            P.GSTR3A_REF_NO,
				            P.GSTR3A_ISSUE_DATE,
				            P.GSTR3A_SERVICE_DATE,
				            P.GSTR3A_DEADLINE,
				            P.GSTR3A_STATUS,
				            P.SECTION62_ELIGIBLE,
				            P.SECTION62_ELIGIBLE_DATE,
				            P.ASMT13_REF_NO,
				            P.ASMT13_ORDER_DATE,
				            P.ASMT13_SERVICE_DATE,
				            P.ASMT13_STATUS,
				            P.ASSESSED_TAX,
				            P.ASSESSED_INTEREST,
				            P.ASSESSED_PENALTY,
				            P.ASSESSED_LATE_FEE,
				            P.ASSESSED_OTHER,
				            P.ASSESSED_TOTAL,
				            P.FIRST_60_DAY_END,
				            P.EXTENDED_60_DAY_END,
				            P.ORDER_WITHDRAWN_DATE,
				            P.ST_JURI,
				            P.OFFICE_CODE,
				            CAST(NULL AS VARCHAR2(200)) AS OFFICE_NAME,
				            P.CREATED_BY,
				            P.UPDATED_BY,
				            P.VERSION_NO
				        FROM GST_3B_DEFAULTER_PROCEEDING P
				        WHERE 1 = 1
				        %s
				    ) X
				)
				WHERE RN > :startRow
				  AND RN <= :endRow
				ORDER BY RN
				""".formatted(filter);

		return jdbc.query(sql, params, this::mapProceeding);
	}

	public Optional<ProceedingRowResponse> findById(Long proceedingId) {

		String sql = """
				SELECT
				    P.ID,
				    P.GSTIN,
				    P.RET_PERIOD,
				    P.RETURN_TYPE,
				    P.FILING_FREQUENCY,
				    P.TAXPAYER_CATEGORY,
				    P.DUE_DATE,
				    P.FILING_DATE,
				    P.STATUS,
				    P.GSTR3A_ELIGIBLE,
				    P.GSTR3A_REF_NO,
				    P.GSTR3A_ISSUE_DATE,
				    P.GSTR3A_SERVICE_DATE,
				    P.GSTR3A_DEADLINE,
				    P.GSTR3A_STATUS,
				    P.SECTION62_ELIGIBLE,
				    P.SECTION62_ELIGIBLE_DATE,
				    P.ASMT13_REF_NO,
				    P.ASMT13_ORDER_DATE,
				    P.ASMT13_SERVICE_DATE,
				    P.ASMT13_STATUS,
				    P.ASSESSED_TAX,
				    P.ASSESSED_INTEREST,
				    P.ASSESSED_PENALTY,
				    P.ASSESSED_LATE_FEE,
				    P.ASSESSED_OTHER,
				    P.ASSESSED_TOTAL,
				    P.FIRST_60_DAY_END,
				    P.EXTENDED_60_DAY_END,
				    P.ORDER_WITHDRAWN_DATE,
				    P.ST_JURI,
				    P.OFFICE_CODE,
				    CAST(NULL AS VARCHAR2(200)) AS OFFICE_NAME,
				    P.CREATED_BY,
				    P.UPDATED_BY,
				    P.VERSION_NO
				FROM GST_3B_DEFAULTER_PROCEEDING P
				WHERE P.ID = :id
				""";

		List<ProceedingRowResponse> rows = jdbc.query(sql, new MapSqlParameterSource("id", proceedingId),
				this::mapProceeding);

		return rows.stream().findFirst();
	}

	public boolean existsById(Long proceedingId) {
		Long count = jdbc.queryForObject("""
				SELECT COUNT(*)
				FROM GST_3B_DEFAULTER_PROCEEDING
				WHERE ID = :id
				""", new MapSqlParameterSource("id", proceedingId), Long.class);
		return count != null && count > 0;
	}

	public List<ProceedingAuditResponse> findAudit(Long proceedingId) {

		String sql = """
				SELECT
				    A.ID,
				    A.PROCEEDING_ID,
				    A.GSTIN,
				    A.RET_PERIOD,
				    A.ACTION_TYPE,
				    A.OLD_STATUS,
				    A.NEW_STATUS,
				    A.REFERENCE_NO,
				    A.OFFICER_HRMS,
				    A.REMARKS,
				    A.ACTION_AT
				FROM GST_DEFAULTER_ACTION_AUDIT A
				WHERE A.PROCEEDING_ID = :proceedingId
				ORDER BY A.ACTION_AT DESC, A.ID DESC
				""";

		return jdbc.query(sql, new MapSqlParameterSource("proceedingId", proceedingId),
				(rs, rowNum) -> new ProceedingAuditResponse(rs.getLong("ID"), rs.getLong("PROCEEDING_ID"),
						rs.getString("GSTIN"), rs.getString("RET_PERIOD"), rs.getString("ACTION_TYPE"),
						rs.getString("OLD_STATUS"), rs.getString("NEW_STATUS"), rs.getString("REFERENCE_NO"),
						rs.getString("OFFICER_HRMS"), rs.getString("REMARKS"),
						toLocalDateTime(rs.getTimestamp("ACTION_AT"))));
	}

	private void appendFilters(StringBuilder sql, MapSqlParameterSource params, String retPeriod, String status,
			String search) {

		if (hasText(retPeriod)) {
			sql.append(" AND P.RET_PERIOD = :retPeriod ");
			params.addValue("retPeriod", retPeriod.trim());
		}

		if (hasText(status)) {
			sql.append(" AND P.STATUS = :status ");
			params.addValue("status", status.trim().toUpperCase(Locale.ROOT));
		}

		if (hasText(search)) {
			String normalized = "%" + search.trim().toUpperCase(Locale.ROOT) + "%";

			sql.append("""
					AND (
					       UPPER(P.GSTIN) LIKE :search
					    OR UPPER(NVL(P.GSTR3A_REF_NO, '')) LIKE :search
					    OR UPPER(NVL(P.ASMT13_REF_NO, '')) LIKE :search
					    OR UPPER(NVL(P.OFFICE_CODE, '')) LIKE :search
					    OR UPPER(NVL(P.ST_JURI, '')) LIKE :search
					)
					""");

			params.addValue("search", normalized);
		}
	}

	private ProceedingRowResponse mapProceeding(ResultSet rs, int rowNum) throws SQLException {

		return new ProceedingRowResponse(rs.getLong("ID"), rs.getString("GSTIN"), rs.getString("RET_PERIOD"),
				rs.getString("RETURN_TYPE"), rs.getString("FILING_FREQUENCY"), rs.getString("TAXPAYER_CATEGORY"),
				toLocalDate(rs.getDate("DUE_DATE")), toLocalDate(rs.getDate("FILING_DATE")), rs.getString("STATUS"),
				rs.getString("GSTR3A_ELIGIBLE"), rs.getString("GSTR3A_REF_NO"),
				toLocalDate(rs.getDate("GSTR3A_ISSUE_DATE")), toLocalDate(rs.getDate("GSTR3A_SERVICE_DATE")),
				toLocalDate(rs.getDate("GSTR3A_DEADLINE")), rs.getString("GSTR3A_STATUS"),
				rs.getString("SECTION62_ELIGIBLE"), toLocalDate(rs.getDate("SECTION62_ELIGIBLE_DATE")),
				rs.getString("ASMT13_REF_NO"), toLocalDate(rs.getDate("ASMT13_ORDER_DATE")),
				toLocalDate(rs.getDate("ASMT13_SERVICE_DATE")), rs.getString("ASMT13_STATUS"),
				rs.getBigDecimal("ASSESSED_TAX"), rs.getBigDecimal("ASSESSED_INTEREST"),
				rs.getBigDecimal("ASSESSED_PENALTY"), rs.getBigDecimal("ASSESSED_LATE_FEE"),
				rs.getBigDecimal("ASSESSED_OTHER"), rs.getBigDecimal("ASSESSED_TOTAL"),
				toLocalDate(rs.getDate("FIRST_60_DAY_END")), toLocalDate(rs.getDate("EXTENDED_60_DAY_END")),
				toLocalDate(rs.getDate("ORDER_WITHDRAWN_DATE")), rs.getString("ST_JURI"), rs.getString("OFFICE_CODE"),
				rs.getString("OFFICE_NAME"), rs.getString("CREATED_BY"), rs.getString("UPDATED_BY"),
				rs.getLong("VERSION_NO"));
	}

	private static LocalDate toLocalDate(Date date) {
		return date == null ? null : date.toLocalDate();
	}

	private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toLocalDateTime();
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
