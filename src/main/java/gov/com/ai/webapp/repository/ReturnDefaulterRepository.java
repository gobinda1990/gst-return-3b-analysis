package gov.com.ai.webapp.repository;

import gov.com.ai.webapp.model.GstDefaulterRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class ReturnDefaulterRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    private static final String SELECT_DEFAULTER_PIPELINE_SQL = """
            SELECT
                gstin,
                ret_period,
                filing_delay_days,
                taxable_value,
                total_output_tax,
                xgb_risk_score,
                CASE
                    WHEN filing_delay_days >= 90 THEN 'Form GST REG-17 (Registration Suspension)'
                    WHEN filing_delay_days >= 30 THEN 'Form GST 3A (Notice to Defaulter)'
                    WHEN filing_delay_days > 15  THEN 'Automated Advisory Warning'
                    ELSE 'Under Observation'
                END AS statutory_action_required,
                CASE
                    WHEN filing_delay_days >= 60 OR xgb_risk_score >= 0.80 THEN 'CRITICAL'
                    WHEN filing_delay_days >= 30 OR xgb_risk_score >= 0.60 THEN 'HIGH'
                    ELSE 'MEDIUM'
                END AS defaulter_risk_level
            FROM gst_ret_3b_summary
            WHERE ret_period = :ret_period
              AND filing_delay_days > 15
            ORDER BY filing_delay_days DESC, xgb_risk_score DESC
            """;

    private static final String COUNT_DEFAULTERS_SQL = """
            SELECT COUNT(*)
            FROM gst_ret_3b_summary
            WHERE ret_period = :ret_period
              AND filing_delay_days > 15
            """;

    private static final RowMapper<GstDefaulterRecord> DEFAULTER_ROW_MAPPER = (rs, rowNum) ->
            GstDefaulterRecord.builder()
                    .gstin(rs.getString("gstin"))
                    .retPeriod(rs.getString("ret_period"))
                    .filingDelayDays(rs.getObject("filing_delay_days") != null ? rs.getInt("filing_delay_days") : 0)
                    .taxableValue(rs.getBigDecimal("taxable_value"))
                    .totalOutputTax(rs.getBigDecimal("total_output_tax"))
                    .xgbRiskScore(rs.getBigDecimal("xgb_risk_score"))
                    .statutoryActionRequired(rs.getString("statutory_action_required"))
                    .defaulterRiskLevel(rs.getString("defaulter_risk_level"))
                    .build();

    /**
     * Fetches all records for a given return period.
     */
    public List<GstDefaulterRecord> fetchDefaulters(String retPeriod) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("ret_period", retPeriod);

        return jdbcTemplate.query(SELECT_DEFAULTER_PIPELINE_SQL, params, DEFAULTER_ROW_MAPPER);
    }

    /**
     * Optional count query (used primarily if querying DB directly without cache).
     */
    public long countDefaulters(String retPeriod) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("ret_period", retPeriod);

        Long count = jdbcTemplate.queryForObject(COUNT_DEFAULTERS_SQL, params, Long.class);
        return count != null ? count : 0L;
    }
}