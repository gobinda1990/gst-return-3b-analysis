package gov.com.ai.webapp.repository;

import gov.com.ai.webapp.model.GstAuditRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class GstAuditJdbcRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    private static final String SELECT_SCRUTINY_PIPELINE_SQL = """
            WITH calculated_metrics AS (
                SELECT
                    gstin,
                    ret_period,
                    taxable_value,
                    total_output_tax,
                    cash_tax_paid,
                    utilized_itc,
                    eligible_itc,
                    excess_itc,
                    filing_delay_days,
                    xgb_risk_score,
                    itc_to_tax_ratio AS itc_ratio
                FROM gst_ret_3b_summary
                WHERE ret_period = :ret_period
            )
            SELECT
                gstin,
                ret_period,
                taxable_value,
                total_output_tax,
                cash_tax_paid,
                utilized_itc,
                eligible_itc,
                excess_itc,
                filing_delay_days,
                ROUND(xgb_risk_score * 100, 2) AS risk_score_pct,
                ROUND(itc_ratio * 100, 2) AS itc_utilization_pct,
                CASE
                    WHEN xgb_risk_score >= 0.85 OR excess_itc > 100000 THEN 'CRITICAL'
                    WHEN xgb_risk_score >= 0.65 OR itc_ratio >= 0.95 THEN 'HIGH'
                    ELSE 'MEDIUM'
                END AS officer_risk_category
            FROM calculated_metrics
            WHERE xgb_risk_score >= 0.65
               OR excess_itc > 0
               OR (
                    total_output_tax > 50000
                    AND itc_ratio >= 0.90
               )
            ORDER BY
                xgb_risk_score DESC,
                excess_itc DESC,
                total_output_tax DESC
            """;

    private static final String COUNT_SCRUTINY_PIPELINE_SQL = """
            SELECT COUNT(*)
            FROM gst_ret_3b_summary
            WHERE ret_period = :ret_period
              AND (
                    xgb_risk_score >= 0.65
                    OR excess_itc > 0
                    OR (total_output_tax > 50000 AND (utilized_itc / NULLIF(total_output_tax, 0)) >= 0.90)
              )
            """;

    private static final RowMapper<GstAuditRecord> AUDIT_ROW_MAPPER = (rs, rowNum) -> {
        BigDecimal excessItc = rs.getBigDecimal("excess_itc");
        String riskCategory = rs.getString("officer_risk_category");
        int delayDays = rs.getInt("filing_delay_days");

        String statutoryAction = "No Action Required";
        String asmt10Reason = "Routine Filing Scrutiny";

        if (excessItc != null && excessItc.compareTo(BigDecimal.valueOf(100000)) > 0) {
            asmt10Reason = "Excess ITC claimed beyond GSTR-2B limit (> ₹1,00,000)";
            statutoryAction = "Issue Form GST ASMT-10";
        } else if ("CRITICAL".equalsIgnoreCase(riskCategory)) {
            asmt10Reason = "High XGB Risk Score and high ITC utilization ratio detected";
            statutoryAction = "Issue Form GST ASMT-10";
        } else if (delayDays > 30) {
            asmt10Reason = "Filing delay exceeds statutory 30-day limit";
            statutoryAction = "Issue Form GST 3A Notice";
        }

        return new GstAuditRecord(
                rs.getString("gstin"),
                rs.getString("ret_period"),
                rs.getBigDecimal("taxable_value"),
                rs.getBigDecimal("total_output_tax"),
                rs.getBigDecimal("cash_tax_paid"),
                rs.getBigDecimal("utilized_itc"),
                rs.getBigDecimal("eligible_itc"),
                excessItc,
                delayDays,
                rs.getBigDecimal("risk_score_pct"),
                rs.getBigDecimal("itc_utilization_pct"),
                riskCategory,
                statutoryAction,
                asmt10Reason
        );
    };

    /**
     * Executes query and maps all matching audit records.
     */
    public List<GstAuditRecord> fetchScrutinyPipeline(String retPeriod) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("ret_period", retPeriod);
        return jdbcTemplate.query(SELECT_SCRUTINY_PIPELINE_SQL, params, AUDIT_ROW_MAPPER);
    }

    /**
     * Database count query (used as fallback or operational monitoring).
     */
    public long countScrutinyRecords(String retPeriod) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("ret_period", retPeriod);
        Long count = jdbcTemplate.queryForObject(COUNT_SCRUTINY_PIPELINE_SQL, params, Long.class);
        return count != null ? count : 0L;
    }
}