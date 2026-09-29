package gov.com.ai.webapp.repository.revenue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import gov.com.ai.webapp.model.revenue.DefaulterHistoryResponse;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
public class GstDefaulterHistoryRepository {

	private final JdbcTemplate jdbcTemplate;

	private static final String HISTORY_SQL = """
			SELECT
			    GSTIN,
			    RET_PERIOD,
			    DUE_DATE,
			    FILING_DATE,
			    FILING_STATUS,
			    DELAY_DAYS,
			    TAXABLE_VALUE,
			    OUTPUT_TAX,
			    DEFAULT_LEVEL,
			    DEFAULT_SCORE,
			    RISK_LEVEL,
			    RISK_SCORE,
			    GSTR3A_ELIGIBLE,
			    GSTR3A_STATUS,
			    SECTION62_CANDIDATE,
			    OFFICER_REVIEW_STATUS
			FROM (
			    SELECT
			        s.GSTIN,
			        s.RET_PERIOD,
			        s.DUE_DATE,
			        s.FILING_DATE,
			        s.FILING_STATUS,
			        s.DELAY_DAYS,
			        s.TAXABLE_VALUE,
			        s.OUTPUT_TAX,
			        s.DEFAULT_LEVEL,
			        s.DEFAULT_SCORE,
			        s.RISK_LEVEL,
			        s.RISK_SCORE,
			        s.GSTR3A_ELIGIBLE,
			        s.GSTR3A_STATUS,
			        s.SECTION62_CANDIDATE,
			        s.OFFICER_REVIEW_STATUS,

			        ROW_NUMBER() OVER (
			            ORDER BY
			                TO_NUMBER(SUBSTR(s.RET_PERIOD, 3, 4)) DESC,
			                TO_NUMBER(SUBSTR(s.RET_PERIOD, 1, 2)) DESC
			        ) AS RN

			    FROM GST_3B_RETURN_DEFAULTER s

			    WHERE s.GSTIN = ?
			      AND s.RET_PERIOD IS NOT NULL
			      AND REGEXP_LIKE(
			            s.RET_PERIOD,
			            '^(0[1-9]|1[0-2])[0-9]{4}$'
			          )
			)
			WHERE RN <= ?
			ORDER BY RN
			""";

	public List<DefaulterHistoryResponse> findHistory(String gstin, int months) {

		log.debug("Fetching GST 3B defaulter history gstin={}, months={}", gstin, months);

		List<DefaulterHistoryResponse> result = jdbcTemplate.query(HISTORY_SQL, this::mapRow, gstin, months);

		log.debug("Fetched {} history records for gstin={}", result.size(), gstin);

		return result;
	}

	private DefaulterHistoryResponse mapRow(ResultSet rs, int rowNum) throws SQLException {

		return DefaulterHistoryResponse.builder()

				.gstin(rs.getString("GSTIN"))

				.retPeriod(rs.getString("RET_PERIOD"))

				.dueDate(toLocalDate(rs.getDate("DUE_DATE")))

				.filingDate(toLocalDate(rs.getDate("FILING_DATE")))

				.filingStatus(rs.getString("FILING_STATUS"))

				.delayDays(getNullableInteger(rs, "DELAY_DAYS"))

				.taxableValue(rs.getBigDecimal("TAXABLE_VALUE"))

				.outputTax(rs.getBigDecimal("OUTPUT_TAX"))

				.defaultLevel(rs.getString("DEFAULT_LEVEL"))

				.defaultScore(rs.getBigDecimal("DEFAULT_SCORE"))

				.riskLevel(rs.getString("RISK_LEVEL"))

				.riskScore(rs.getBigDecimal("RISK_SCORE"))

				.gstr3aEligible(rs.getString("GSTR3A_ELIGIBLE"))

				.gstr3aStatus(rs.getString("GSTR3A_STATUS"))

				.section62Candidate(rs.getString("SECTION62_CANDIDATE"))

				.officerReviewStatus(rs.getString("OFFICER_REVIEW_STATUS"))

				.build();
	}

	private Integer getNullableInteger(ResultSet rs, String column) throws SQLException {

		int value = rs.getInt(column);

		return rs.wasNull() ? null : value;
	}

	private java.time.LocalDate toLocalDate(Date date) {

		return date == null ? null : date.toLocalDate();
	}
}
