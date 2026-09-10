package gov.com.ai.webapp.repository.dto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
public class GstItcBulkRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    private static final String BULK_SQL = """
        SELECT
            GSTIN,
            RET_PERIOD,

            TAXABLE_VALUE,
            TOTAL_OUTPUT_TAX,

            ELIGIBLE_ITC,
            UTILIZED_ITC,
            REVERSED_ITC,
            INELIGIBLE_ITC,
            EXCESS_ITC,

            RCM_TOTAL_TAX,

            ITC_ISRC_IGST,
            ITC_ISRC_CGST,
            ITC_ISRC_SGST,
            ITC_ISRC_CESS,

            RCM_PAYMENT_TOTAL,

            XGB_RISK_SCORE,
            DL4J_ANOMALY_SCORE

        FROM GST_RET_3B_SUMMARY

        WHERE RET_PERIOD = :retPeriod

        ORDER BY GSTIN
        """;

    private static final RowMapper<GstItcDbRow> ROW_MAPPER =
            new GstItcRowMapper();

    public List<GstItcDbRow> findByReturnPeriod(String retPeriod) {

        validateReturnPeriod(retPeriod);

        try {

            MapSqlParameterSource params =
                    new MapSqlParameterSource()
                            .addValue("retPeriod", retPeriod);

            List<GstItcDbRow> result =
                    jdbcTemplate.query(
                            BULK_SQL,
                            params,
                            ROW_MAPPER
                    );

            log.info(
                    "Bulk ITC analytics data loaded. retPeriod={}, records={}",
                    retPeriod,
                    result.size()
            );

            return result;

        } catch (DataAccessException ex) {

            log.error(
                    "Bulk ITC query failed. retPeriod={}, error={}",
                    retPeriod,
                    getRootCauseMessage(ex),
                    ex
            );

            throw ex;
        }
    }

    private void validateReturnPeriod(String retPeriod) {

        if (retPeriod == null ||
                !retPeriod.matches("^(0[1-9]|1[0-2])\\d{4}$")) {

            throw new IllegalArgumentException(
                    "Invalid RET_PERIOD: " + retPeriod +
                    ". Expected MMYYYY, e.g. 072026"
            );
        }
    }

    private String getRootCauseMessage(Throwable throwable) {

        Throwable root = throwable;

        while (root.getCause() != null) {
            root = root.getCause();
        }

        return root.getMessage();
    }

    private static final class GstItcRowMapper
            implements RowMapper<GstItcDbRow> {

        @Override
        public GstItcDbRow mapRow(
                ResultSet rs,
                int rowNum
        ) throws SQLException {

            return new GstItcDbRow(

                    rs.getString("GSTIN"),
                    rs.getString("RET_PERIOD"),

                    rs.getBigDecimal("TAXABLE_VALUE"),
                    rs.getBigDecimal("TOTAL_OUTPUT_TAX"),

                    rs.getBigDecimal("ELIGIBLE_ITC"),
                    rs.getBigDecimal("UTILIZED_ITC"),
                    rs.getBigDecimal("REVERSED_ITC"),
                    rs.getBigDecimal("INELIGIBLE_ITC"),
                    rs.getBigDecimal("EXCESS_ITC"),

                    rs.getBigDecimal("RCM_TOTAL_TAX"),

                    rs.getBigDecimal("ITC_ISRC_IGST"),
                    rs.getBigDecimal("ITC_ISRC_CGST"),
                    rs.getBigDecimal("ITC_ISRC_SGST"),
                    rs.getBigDecimal("ITC_ISRC_CESS"),

                    rs.getBigDecimal("RCM_PAYMENT_TOTAL"),

                    rs.getBigDecimal("XGB_RISK_SCORE"),
                    rs.getBigDecimal("DL4J_ANOMALY_SCORE")
            );
        }
    }

    public record GstItcDbRow(

            String gstin,
            String retPeriod,

            BigDecimal taxableValue,
            BigDecimal totalOutputTax,

            BigDecimal eligibleItc,
            BigDecimal utilizedItc,
            BigDecimal reversedItc,
            BigDecimal ineligibleItc,
            BigDecimal excessItc,

            BigDecimal rcmTotalTax,

            BigDecimal rcmItcIgst,
            BigDecimal rcmItcCgst,
            BigDecimal rcmItcSgst,
            BigDecimal rcmItcCess,

            BigDecimal rcmPaymentTotal,

            BigDecimal xgbRiskScore,
            BigDecimal dl4jAnomalyScore
    ) {
    }
}