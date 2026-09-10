package gov.com.ai.webapp.repository.dto;


import gov.com.ai.webapp.model.dto.GstMonthlyTrend;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Slf4j
public class GstAnalyticsRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String FETCH_ANALYTICS_SQL = """
            SELECT
                GSTIN,
                STATE_CODE,
                COUNT(*) AS TOTAL_RETURNS,
                MIN(FILING_DATE) AS FIRST_FILING_DATE,
                MAX(FILING_DATE) AS LATEST_FILING_DATE,
                COALESCE(SUM(TAXABLE_VALUE), 0) AS TAXABLE_TURNOVER,
                COALESCE(SUM(TOTAL_OUTPUT_TAX), 0) AS OUTPUT_TAX,
                COALESCE(SUM(ELIGIBLE_ITC), 0) AS ELIGIBLE_ITC,
                COALESCE(SUM(UTILIZED_ITC), 0) AS UTILIZED_ITC,
                COALESCE(SUM(REVERSED_ITC), 0) AS REVERSED_ITC,
                COALESCE(SUM(INELIGIBLE_ITC), 0) AS INELIGIBLE_ITC,
                COALESCE(SUM(EXCESS_ITC), 0) AS EXCESS_ITC,
                COALESCE(SUM(CASH_TAX_PAID), 0) AS CASH_TAX_PAID,
                COALESCE(SUM(ITC_PAYMENT_TOTAL), 0) AS ITC_PAYMENT_TOTAL,
                COALESCE(SUM(INTEREST_PAID), 0) AS INTEREST_PAID,
                COALESCE(SUM(LATE_FEE_PAID), 0) AS LATE_FEE_PAID,
                COALESCE(SUM(OUTPUT_IGST), 0) AS OUTPUT_IGST,
                COALESCE(SUM(OUTPUT_CGST), 0) AS OUTPUT_CGST,
                COALESCE(SUM(OUTPUT_SGST), 0) AS OUTPUT_SGST,
                COALESCE(SUM(OUTPUT_CESS), 0) AS OUTPUT_CESS,
                COALESCE(SUM(RCM_TOTAL_TAX), 0) AS RCM_TOTAL_TAX,
                COALESCE(SUM(ZERO_RATED_VALUE), 0) AS ZERO_RATED_VALUE,
                COALESCE(SUM(NIL_EXEMPT_VALUE), 0) AS NIL_EXEMPT_VALUE,
                COALESCE(SUM(NON_GST_VALUE), 0) AS NON_GST_VALUE,
                COALESCE(SUM(RCM_TAXABLE_VALUE), 0) AS RCM_TAXABLE_VALUE,
                AVG(COALESCE(FILING_DELAY_DAYS, 0)) AS AVG_DELAY,
                MAX(COALESCE(FILING_DELAY_DAYS, 0)) AS MAX_DELAY,
                SUM(
                    CASE
                        WHEN FILING_DATE IS NOT NULL AND DUE_DATE IS NOT NULL AND FILING_DATE > DUE_DATE THEN 1
                        ELSE 0
                    END
                ) AS DELAYED_RETURNS,
                SUM(
                    CASE
                        WHEN FILING_DATE IS NOT NULL AND DUE_DATE IS NOT NULL AND FILING_DATE <= DUE_DATE THEN 1
                        ELSE 0
                    END
                ) AS ONTIME_RETURNS,
                MAX(COALESCE(XGB_RISK_SCORE, 0)) AS XGB_RISK_SCORE,
                MAX(COALESCE(DL4J_ANOMALY_SCORE, 0)) AS ANOMALY_SCORE
            FROM GST_RET_3B_SUMMARY
            WHERE GSTIN = ?
            GROUP BY GSTIN, STATE_CODE
            """;

    private static final String FETCH_MONTHLY_TREND_SQL = """
            SELECT * FROM (
                SELECT
                    RET_PERIOD,
                    COALESCE(TAXABLE_VALUE, 0) AS TAXABLE_VALUE,
                    COALESCE(TOTAL_OUTPUT_TAX, 0) AS TOTAL_OUTPUT_TAX,
                    COALESCE(ELIGIBLE_ITC, 0) AS ELIGIBLE_ITC,
                    COALESCE(UTILIZED_ITC, 0) AS UTILIZED_ITC,
                    GREATEST(
                        COALESCE(TOTAL_OUTPUT_TAX, 0) - COALESCE(UTILIZED_ITC, 0), 0
                    ) AS TAX_PAYABLE,
                    COALESCE(CASH_TAX_PAID, 0) AS CASH_TAX_PAID,
                    COALESCE(FILING_DELAY_DAYS, 0) AS FILING_DELAY_DAYS,
                    COALESCE(ITC_UTILIZATION_RATIO, 0) AS ITC_UTILIZATION_RATIO,
                    COALESCE(ITC_TO_TAX_RATIO, 0) AS ITC_TO_TAX_RATIO
                FROM GST_RET_3B_SUMMARY
                WHERE GSTIN = ?
                ORDER BY TO_DATE(RET_PERIOD, 'MMYYYY') DESC
            ) WHERE ROWNUM <= ?
            """;

    public Optional<GstAnalyticsRow> fetchAnalytics(String gstin) {
        if (gstin == null || gstin.isBlank()) {
            throw new IllegalArgumentException("GSTIN must not be null or empty");
        }

        try {
            List<GstAnalyticsRow> results = jdbcTemplate.query(FETCH_ANALYTICS_SQL, this::mapAnalyticsRow, gstin);
            return results.stream().findFirst();
        } catch (DataAccessException ex) {
            log.error("Database access error fetching GST analytics for GSTIN: {}", gstin, ex);
            throw ex;
        }
    }

    public List<GstMonthlyTrend> fetchMonthlyTrend(String gstin, int months) {
        if (gstin == null || gstin.isBlank()) {
            throw new IllegalArgumentException("GSTIN must not be null or empty");
        }
        if (months < 1 || months > 36) {
            throw new IllegalArgumentException("Months parameter must be between 1 and 36");
        }

        try {
            return jdbcTemplate.query(FETCH_MONTHLY_TREND_SQL, MONTHLY_TREND_ROW_MAPPER, gstin, months);
        } catch (DataAccessException ex) {
            log.error("Database access error fetching monthly trend for GSTIN: {}", gstin, ex);
            throw ex;
        }
    }

    private GstAnalyticsRow mapAnalyticsRow(ResultSet rs, int rowNum) throws SQLException {
        return new GstAnalyticsRow(
                rs.getString("GSTIN"),
                rs.getString("STATE_CODE"),
                rs.getInt("TOTAL_RETURNS"),
                rs.getDate("FIRST_FILING_DATE"),
                rs.getDate("LATEST_FILING_DATE"),
                getBigDecimal(rs, "TAXABLE_TURNOVER"),
                getBigDecimal(rs, "OUTPUT_TAX"),
                getBigDecimal(rs, "ELIGIBLE_ITC"),
                getBigDecimal(rs, "UTILIZED_ITC"),
                getBigDecimal(rs, "REVERSED_ITC"),
                getBigDecimal(rs, "INELIGIBLE_ITC"),
                getBigDecimal(rs, "EXCESS_ITC"),
                getBigDecimal(rs, "CASH_TAX_PAID"),
                getBigDecimal(rs, "ITC_PAYMENT_TOTAL"),
                getBigDecimal(rs, "INTEREST_PAID"),
                getBigDecimal(rs, "LATE_FEE_PAID"),
                getBigDecimal(rs, "OUTPUT_IGST"),
                getBigDecimal(rs, "OUTPUT_CGST"),
                getBigDecimal(rs, "OUTPUT_SGST"),
                getBigDecimal(rs, "OUTPUT_CESS"),
                getBigDecimal(rs, "RCM_TOTAL_TAX"),
                getBigDecimal(rs, "ZERO_RATED_VALUE"),
                getBigDecimal(rs, "NIL_EXEMPT_VALUE"),
                getBigDecimal(rs, "NON_GST_VALUE"),
                getBigDecimal(rs, "RCM_TAXABLE_VALUE"),
                getBigDecimal(rs, "AVG_DELAY"),
                rs.getInt("MAX_DELAY"),
                rs.getInt("DELAYED_RETURNS"),
                rs.getInt("ONTIME_RETURNS"),
                getBigDecimal(rs, "XGB_RISK_SCORE"),
                getBigDecimal(rs, "ANOMALY_SCORE")
        );
    }

    private static final RowMapper<GstMonthlyTrend> MONTHLY_TREND_ROW_MAPPER = (rs, rowNum) -> new GstMonthlyTrend(
            rs.getString("RET_PERIOD"),
            getBigDecimal(rs, "TAXABLE_VALUE"),
            getBigDecimal(rs, "TOTAL_OUTPUT_TAX"),
            getBigDecimal(rs, "ELIGIBLE_ITC"),
            getBigDecimal(rs, "UTILIZED_ITC"),
            getBigDecimal(rs, "TAX_PAYABLE"),
            getBigDecimal(rs, "CASH_TAX_PAID"),
            getBigDecimal(rs, "FILING_DELAY_DAYS"),
            getBigDecimal(rs, "ITC_UTILIZATION_RATIO"),
            getBigDecimal(rs, "ITC_TO_TAX_RATIO")
    );

    private static BigDecimal getBigDecimal(ResultSet rs, String columnName) throws SQLException {
        BigDecimal value = rs.getBigDecimal(columnName);
        return value != null ? value : BigDecimal.ZERO;
    }
}