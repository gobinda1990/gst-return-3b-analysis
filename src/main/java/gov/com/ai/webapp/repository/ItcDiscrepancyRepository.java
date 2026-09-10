package gov.com.ai.webapp.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import gov.com.ai.webapp.model.ItcSummaryRow;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class ItcDiscrepancyRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	private static final String SQL = """
			SELECT
			    GSTIN,
			    RET_PERIOD,
			    ELIGIBLE_ITC,
			    UTILIZED_ITC,
			    REVERSED_ITC,
			    INELIGIBLE_ITC,
			    EXCESS_ITC,
			    TOTAL_OUTPUT_TAX,
			    RCM_TOTAL_TAX,
			    RCM_TOTAL_ITC,
			    ITC_UTILIZATION_RATIO,
			    ITC_TO_TAX_RATIO
			FROM GST_RET_3B_SUMMARY
			WHERE GSTIN = :gstin
			  AND REGEXP_LIKE(RET_PERIOD, '^[0-9]{6}$')
			ORDER BY
			    TO_DATE(RET_PERIOD, 'MMYYYY') DESC
			FETCH FIRST 12 ROWS ONLY
			""";

	public List<ItcSummaryRow> findLast12Months(String gstin) {

		MapSqlParameterSource params = new MapSqlParameterSource("gstin", gstin);

		return jdbcTemplate.query(SQL, params, new ItcRowMapper());
	}

	private static final class ItcRowMapper implements RowMapper<ItcSummaryRow> {

		@Override
		public ItcSummaryRow mapRow(ResultSet rs, int rowNum) throws SQLException {

			return new ItcSummaryRow(rs.getString("GSTIN"), rs.getString("RET_PERIOD"),
					rs.getBigDecimal("ELIGIBLE_ITC"), rs.getBigDecimal("UTILIZED_ITC"),
					rs.getBigDecimal("REVERSED_ITC"), rs.getBigDecimal("INELIGIBLE_ITC"),
					rs.getBigDecimal("EXCESS_ITC"), rs.getBigDecimal("TOTAL_OUTPUT_TAX"),
					rs.getBigDecimal("RCM_TOTAL_TAX"), rs.getBigDecimal("RCM_TOTAL_ITC"),
					rs.getBigDecimal("ITC_UTILIZATION_RATIO"), rs.getBigDecimal("ITC_TO_TAX_RATIO"));
		}
	}
}
