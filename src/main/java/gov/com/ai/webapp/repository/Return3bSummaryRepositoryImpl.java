package gov.com.ai.webapp.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import gov.com.ai.webapp.model.DashboardDTOs.ComplianceAlertDTO;
import gov.com.ai.webapp.model.DashboardDTOs.ForecastSeriesDTO;
import gov.com.ai.webapp.model.DashboardDTOs.FraudSeriesDTO;
import gov.com.ai.webapp.model.DashboardDTOs.HighRiskGstinDTO;
import gov.com.ai.webapp.model.DashboardDTOs.TaxCollectionSummaryDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Repository
@RequiredArgsConstructor
public class Return3bSummaryRepositoryImpl implements Return3bSummaryRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	@Override
	public Optional<SummaryKPIs> fetchAggregatedKPIs(String retPeriod, String stateCode) {
		return fetchAggregatedKPIs(null, retPeriod, stateCode);
	}

	@Override
	public Optional<SummaryKPIs> fetchAggregatedKPIs(String gstin, String retPeriod, String stateCode) {
		StringBuilder sql = new StringBuilder("SELECT " + "    COUNT(DISTINCT GSTIN) AS TOTAL_GSTINS, "
				+ "    COALESCE(SUM(TAXABLE_VALUE), 0) AS TOTAL_TAXABLE_VALUE, "
				+ "    COALESCE(SUM(CASH_TAX_PAID), 0) AS TOTAL_CASH_PAID, "
				+ "    COALESCE(SUM(UTILIZED_ITC), 0) AS TOTAL_UTILIZED_ITC, "
				+ "    COALESCE(SUM(EXCESS_ITC), 0) AS TOTAL_EXCESS_ITC, "
				+ "    COALESCE(SUM(RCM_TOTAL_TAX), 0) AS TOTAL_RCM_TAX, "
				+ "    COALESCE(SUM(NON_GST_VALUE + ZERO_RATED_VALUE), 0) AS TOTAL_ECOMMERCE, "
				+ "    COALESCE(AVG(CASH_PAYMENT_RATIO), 0.0) AS AVG_CASH_RATIO, "
				+ "    COALESCE(AVG(ITC_UTILIZATION_RATIO), 0.0) AS AVG_ITC_RATIO, "
				+ "    COALESCE(AVG(XGB_RISK_SCORE), 0.0) AS AVG_XGB_SCORE, "
				+ "    COALESCE(AVG(FILING_DELAY_DAYS), 0.0) AS AVG_DELAY, "
				+ "    COUNT(CASE WHEN (ITC_UTILIZATION_RATIO >= 0.98 AND CASH_PAYMENT_RATIO <= 0.02) OR EXCESS_ITC > 0 THEN 1 END) AS FRAUD_ALERTS_COUNT, "
				+ "    COUNT(CASE WHEN FILING_DELAY_DAYS > 15 THEN 1 END) AS PREDICTED_DEFAULTS_COUNT "
				+ "FROM gst_ret_3b_summary ");

		MapSqlParameterSource params = new MapSqlParameterSource();
		appendWhereClause(sql, params, gstin, retPeriod, stateCode);

		try {
			SummaryKPIs result = jdbcTemplate.query(sql.toString(), params, rs -> {
				if (rs.next()) {
					return new SummaryKPIs(rs.getLong("TOTAL_GSTINS"), rs.getBigDecimal("TOTAL_TAXABLE_VALUE"),
							rs.getBigDecimal("TOTAL_CASH_PAID"), rs.getBigDecimal("TOTAL_UTILIZED_ITC"),
							rs.getBigDecimal("TOTAL_EXCESS_ITC"), rs.getBigDecimal("TOTAL_RCM_TAX"),
							rs.getBigDecimal("TOTAL_ECOMMERCE"), rs.getDouble("AVG_CASH_RATIO"),
							rs.getDouble("AVG_ITC_RATIO"), rs.getDouble("AVG_XGB_SCORE"), rs.getDouble("AVG_DELAY"),
							rs.getLong("FRAUD_ALERTS_COUNT"), rs.getLong("PREDICTED_DEFAULTS_COUNT"));
				}
				return null;
			});
			return Optional.ofNullable(result);
		} catch (DataAccessException e) {
			log.error("Failed to fetch KPIs for GSTIN={}, RET_PERIOD={}, STATE={}", gstin, retPeriod, stateCode, e);
			return Optional.empty();
		}
	}

	@Override
	public List<HighRiskGstinDTO> fetchTopHighRiskGstins(String retPeriod, String stateCode, int limit) {
		return fetchTopHighRiskGstins(null, retPeriod, stateCode, limit);
	}

	public List<HighRiskGstinDTO> fetchTopHighRiskGstins(String gstin, String retPeriod, String stateCode, int limit) {
		StringBuilder innerSql = new StringBuilder(
				"SELECT " + "    GSTIN, " + "    RET_PERIOD, " + "    STATE_CODE, " + "    EXCESS_ITC, " + // Included
																											// for ORDER
																											// BY
																											// integrity
						"    COALESCE(TAXABLE_VALUE, 0) AS TAXABLE_VALUE, "
						+ "    COALESCE(ITC_UTILIZATION_RATIO, 0) AS ITC_UTILIZATION_RATIO, "
						+ "    COALESCE(CASH_PAYMENT_RATIO, 0) AS CASH_PAYMENT_RATIO, "
						+ "    COALESCE(FILING_DELAY_DAYS, 0) AS FILING_DELAY_DAYS, "
						+ "    COALESCE(XGB_RISK_SCORE, 0) AS XGB_RISK_SCORE, " + "    DL4J_ANOMALY_SCORE, "
						+ "    CASE " + "        WHEN XGB_RISK_SCORE >= 0.85 OR EXCESS_ITC > 100000 THEN 'CRITICAL' "
						+ "        WHEN XGB_RISK_SCORE >= 0.65 THEN 'HIGH' " + "        ELSE 'MEDIUM' "
						+ "    END AS RISK_CATEGORY " + "FROM gst_ret_3b_summary ");

		MapSqlParameterSource params = new MapSqlParameterSource();
		appendWhereClause(innerSql, params, gstin, retPeriod, stateCode);

		innerSql.append(" ORDER BY XGB_RISK_SCORE DESC, EXCESS_ITC DESC");

		// Oracle subquery pattern replacing FETCH FIRST :limit ROWS ONLY
		String sql = "SELECT * FROM (" + innerSql.toString() + ") WHERE ROWNUM <= :limit";
		params.addValue("limit", Math.max(1, limit));

		try {
			return jdbcTemplate.query(sql, params, (rs, rowNum) -> HighRiskGstinDTO.builder()
					.gstin(rs.getString("GSTIN")).retPeriod(rs.getString("RET_PERIOD"))
					.stateCode(rs.getString("STATE_CODE")).taxableValue(rs.getBigDecimal("TAXABLE_VALUE"))
					.itcUtilizationRatio(rs.getDouble("ITC_UTILIZATION_RATIO"))
					.cashPaymentRatio(rs.getDouble("CASH_PAYMENT_RATIO"))
					.filingDelayDays(rs.getInt("FILING_DELAY_DAYS")).xgbRiskScore(rs.getDouble("XGB_RISK_SCORE"))
					.dl4jAnomalyScore(getNullableDouble(rs, "DL4J_ANOMALY_SCORE"))
					.riskCategory(rs.getString("RISK_CATEGORY")).build());
		} catch (DataAccessException e) {
			log.error("Failed to fetch top high-risk GSTINs for GSTIN={}, RET_PERIOD={}, STATE={}", gstin, retPeriod,
					stateCode, e);
			return Collections.emptyList();
		}
	}

	@Override
	public List<ComplianceAlertDTO> fetchRecentComplianceAlerts(String retPeriod, String stateCode, int limit) {
		return fetchRecentComplianceAlerts(null, retPeriod, stateCode, limit);
	}

	@Override
	public List<ComplianceAlertDTO> fetchRecentComplianceAlerts(String gstin, String retPeriod, String stateCode,
			int limit) {
		MapSqlParameterSource params = new MapSqlParameterSource();
		List<String> conditions = extractWhereConditions(gstin, retPeriod, stateCode, params);

		// Core anomaly filter
		conditions.add("(EXCESS_ITC > 0 OR (RCM_TOTAL_TAX = 0 AND TAXABLE_VALUE > 1000000) OR FILING_DELAY_DAYS > 30)");

		String whereClause = " WHERE " + String.join(" AND ", conditions);

		// Oracle-safe outer query wrapper using ROWNUM
		String sql = "SELECT * FROM (" + "    SELECT " + "        GSTIN || '_' || RET_PERIOD AS ALERT_ID, "
				+ "        GSTIN, " + "        RET_PERIOD, " + "        COALESCE(EXCESS_ITC, 0) AS EXCESS_ITC, "
				+ "        TO_CHAR(FILING_DATE, 'YYYY-MM-DD') AS FILING_DATE, " + "        CASE "
				+ "            WHEN EXCESS_ITC > 0 AND CASH_PAYMENT_RATIO <= 0.01 "
				+ "                THEN 'Zero cash settlement with excess ITC claimed against GSTR-2B' "
				+ "            WHEN RCM_TOTAL_TAX = 0 AND TAXABLE_VALUE > 1000000 "
				+ "                THEN 'Potential RCM tax evasion detected on high turnover operations' "
				+ "            WHEN FILING_DELAY_DAYS > 30 "
				+ "                THEN 'Severe filing delay exceeding 30-day statutory threshold' "
				+ "            ELSE 'Abnormal ITC utilization pattern flagged by system' "
				+ "        END AS ALERT_MESSAGE, " + "        CASE "
				+ "            WHEN EXCESS_ITC > 100000 THEN 'CRITICAL' "
				+ "            WHEN EXCESS_ITC > 0 THEN 'HIGH' " + "            ELSE 'WARNING' "
				+ "        END AS SEVERITY " + "    FROM gst_ret_3b_summary " + whereClause
				+ "    ORDER BY TO_DATE(LPAD(RET_PERIOD, 6, '0'), 'MMYYYY') DESC " + ") WHERE ROWNUM <= :limit";

		params.addValue("limit", Math.max(1, limit));

		try {
			return jdbcTemplate.query(sql, params,
					(rs, rowNum) -> ComplianceAlertDTO.builder().id(rs.getString("ALERT_ID"))
							.gstin(rs.getString("GSTIN")).retPeriod(rs.getString("RET_PERIOD"))
							.message(rs.getString("ALERT_MESSAGE")).excessItc(rs.getBigDecimal("EXCESS_ITC"))
							.formattedDate(rs.getString("FILING_DATE") != null ? rs.getString("FILING_DATE")
									: rs.getString("RET_PERIOD"))
							.severity(rs.getString("SEVERITY")).build());
		} catch (DataAccessException e) {
			log.error("Failed to fetch compliance alerts for GSTIN={}, RET_PERIOD={}, STATE={}", gstin, retPeriod,
					stateCode, e);
			return Collections.emptyList();
		}
	}

	@Override
	public List<ForecastSeriesDTO> fetchForecastSeries(String stateCode, int monthsHistory) {
		return fetchForecastSeries(null, stateCode, monthsHistory);
	}

	@Override
	public List<ForecastSeriesDTO> fetchForecastSeries(String gstin, String stateCode, int monthsHistory) {
		StringBuilder innerSql = new StringBuilder(
				"SELECT " + "    RET_PERIOD, " + "    COALESCE(SUM(TOTAL_OUTPUT_TAX), 0) AS ACTUAL_OUTPUT_TAX, "
						+ "    COALESCE(SUM(CASH_TAX_PAID), 0) AS CASH_INFLOW " + "FROM gst_ret_3b_summary ");

		MapSqlParameterSource params = new MapSqlParameterSource();
		appendWhereClause(innerSql, params, gstin, null, stateCode);

		// Safely pad single-digit months to 6 characters before date conversion
		innerSql.append(" GROUP BY RET_PERIOD ORDER BY TO_DATE(LPAD(TRIM(RET_PERIOD), 6, '0'), 'MMYYYY') DESC");

		// Subquery wrapper for Oracle parameter-safe pagination
		String sql = "SELECT * FROM (" + innerSql.toString() + ") WHERE ROWNUM <= :monthsHistory";

		params.addValue("monthsHistory", Math.max(1, monthsHistory));

		try {
			List<ForecastSeriesDTO> list = jdbcTemplate.query(sql, params,
					(rs, rowNum) -> ForecastSeriesDTO.builder().period(rs.getString("RET_PERIOD"))
							.actualOutputTax(rs.getBigDecimal("ACTUAL_OUTPUT_TAX"))
							.cashInflow(rs.getBigDecimal("CASH_INFLOW")).build());

			Collections.reverse(list);
			return list;
		} catch (DataAccessException e) {
			log.error("Failed to fetch forecast time series for GSTIN={}, STATE={}", gstin, stateCode, e);
			return Collections.emptyList();
		}
	}

	@Override
	public List<FraudSeriesDTO> fetchFraudSeries(String stateCode, int monthsHistory) {
		return fetchFraudSeries(null, stateCode, monthsHistory);
	}

	@Override
	public List<FraudSeriesDTO> fetchFraudSeries(String gstin, String stateCode, int monthsHistory) {
		StringBuilder innerSql = new StringBuilder(
				"SELECT " + "    RET_PERIOD, " + "    COALESCE(SUM(UTILIZED_ITC), 0) AS TOTAL_ITC_CLAIMED, "
						+ "    COALESCE(SUM(EXCESS_ITC), 0) AS FLAGGED_EXCESS_ITC, "
						+ "    COUNT(CASE WHEN XGB_RISK_SCORE > 0.70 THEN 1 END) AS ANOMALY_COUNT "
						+ "FROM gst_ret_3b_summary ");

		MapSqlParameterSource params = new MapSqlParameterSource();
		appendWhereClause(innerSql, params, gstin, null, stateCode);

		// LPAD ensures consistent 6-digit MMYYYY format before calling TO_DATE
		innerSql.append(" GROUP BY RET_PERIOD ORDER BY TO_DATE(LPAD(TRIM(RET_PERIOD), 6, '0'), 'MMYYYY') DESC");

		// Oracle subquery pattern replacing FETCH FIRST :monthsHistory ROWS ONLY
		String sql = "SELECT * FROM (" + innerSql.toString() + ") WHERE ROWNUM <= :monthsHistory";

		params.addValue("monthsHistory", Math.max(1, monthsHistory));

		try {
			List<FraudSeriesDTO> list = jdbcTemplate.query(sql, params,
					(rs, rowNum) -> FraudSeriesDTO.builder().period(rs.getString("RET_PERIOD"))
							.totalItcClaimed(rs.getBigDecimal("TOTAL_ITC_CLAIMED"))
							.flaggedExcessItc(rs.getBigDecimal("FLAGGED_EXCESS_ITC"))
							.anomalyCount(rs.getLong("ANOMALY_COUNT")).build());

			Collections.reverse(list);
			return list;
		} catch (DataAccessException e) {
			log.error("Failed to fetch fraud time series for GSTIN={}, STATE={}", gstin, stateCode, e);
			return Collections.emptyList();
		}
	}

	@Override
	public List<TaxCollectionSummaryDTO> fetchTaxCollectionSummary(String gstin, String retPeriod, String stateCode,
			int monthsHistory) {
		int limit = monthsHistory > 0 ? monthsHistory : 12;

		StringBuilder innerSql = new StringBuilder(
				"SELECT " + "    RET_PERIOD, " + "    COALESCE(SUM(OUTPUT_IGST), 0) AS TOTAL_IGST, "
						+ "    COALESCE(SUM(OUTPUT_CGST), 0) AS TOTAL_CGST, "
						+ "    COALESCE(SUM(OUTPUT_SGST), 0) AS TOTAL_SGST, "
						+ "    COALESCE(SUM(OUTPUT_CESS), 0) AS TOTAL_CESS, "
						+ "    COALESCE(SUM(TOTAL_OUTPUT_TAX), 0) AS TOTAL_TAX, "
						+ "    COALESCE(SUM(CASH_IGST_PAID), 0) AS CASH_IGST_PAID, "
						+ "    COALESCE(SUM(CASH_CGST_PAID), 0) AS CASH_CGST_PAID, "
						+ "    COALESCE(SUM(CASH_SGST_PAID), 0) AS CASH_SGST_PAID, "
						+ "    COALESCE(SUM(CASH_CESS_PAID), 0) AS CASH_CESS_PAID, "
						+ "    COALESCE(SUM(CASH_TAX_PAID), 0) AS TOTAL_CASH_PAID, " + "    CASE "
						+ "        WHEN SUM(TOTAL_OUTPUT_TAX) > 0 "
						+ "            THEN ROUND((SUM(CASH_TAX_PAID) / SUM(TOTAL_OUTPUT_TAX)) * 100, 2) "
						+ "        ELSE 0 " + "    END AS PAYMENT_RATIO_PERCENT " + "FROM gst_ret_3b_summary ");

		MapSqlParameterSource params = new MapSqlParameterSource();
		appendWhereClause(innerSql, params, gstin, retPeriod, stateCode);

		// LPAD prevents invalid date format crashes when RET_PERIOD months are single
		// digits (e.g. 32024 vs 032024)
		innerSql.append(" GROUP BY RET_PERIOD ORDER BY TO_DATE(RET_PERIOD, 'MMYYYY') DESC");

		String sql = "SELECT * FROM (" + innerSql.toString() + ") WHERE ROWNUM <= :limit";
		params.addValue("limit", limit);

		try {
			List<TaxCollectionSummaryDTO> list = jdbcTemplate.query(sql, params, (rs, rowNum) -> TaxCollectionSummaryDTO
					.builder().period(rs.getString("RET_PERIOD")).igst(rs.getBigDecimal("TOTAL_IGST"))
					.cgst(rs.getBigDecimal("TOTAL_CGST")).sgst(rs.getBigDecimal("TOTAL_SGST"))
					.cess(rs.getBigDecimal("TOTAL_CESS")).totalTax(rs.getBigDecimal("TOTAL_TAX"))
					.cashIgstPaid(rs.getBigDecimal("CASH_IGST_PAID")).cashCgstPaid(rs.getBigDecimal("CASH_CGST_PAID"))
					.cashSgstPaid(rs.getBigDecimal("CASH_SGST_PAID")).cashCessPaid(rs.getBigDecimal("CASH_CESS_PAID"))
					.cashTaxPaid(rs.getBigDecimal("TOTAL_CASH_PAID"))
					.paymentRatioPercent(rs.getDouble("PAYMENT_RATIO_PERCENT")).build());

			Collections.reverse(list);
			return list;
		} catch (DataAccessException e) {
			log.error("Failed to fetch tax collection summary for GSTIN={}, RET_PERIOD={}, STATE={}", gstin, retPeriod,
					stateCode, e);
			return Collections.emptyList();
		}
	}

	// --- Dynamic SQL Filtering Helpers ---

	private void appendWhereClause(StringBuilder sql, MapSqlParameterSource params, String gstin, String retPeriod,
			String stateCode) {
		List<String> conditions = extractWhereConditions(gstin, retPeriod, stateCode, params);
		if (!conditions.isEmpty()) {
			sql.append(" WHERE ").append(String.join(" AND ", conditions));
		}
	}

	private List<String> extractWhereConditions(String gstin, String retPeriod, String stateCode,
			MapSqlParameterSource params) {
		List<String> conditions = new ArrayList<>(3);
		if (hasValue(gstin)) {
			conditions.add("GSTIN = :gstin");
			params.addValue("gstin", gstin.trim().toUpperCase());
		}
		if (hasValue(retPeriod)) {
			conditions.add("RET_PERIOD = :retPeriod");
			params.addValue("retPeriod", retPeriod.trim());
		}
		if (hasValue(stateCode)) {
			conditions.add("STATE_CODE = :stateCode");
			params.addValue("stateCode", stateCode.trim());
		}
		return conditions;
	}

	private boolean hasValue(String str) {
		return str != null && !str.trim().isEmpty();
	}

	private Double getNullableDouble(ResultSet rs, String columnName) throws SQLException {
		double val = rs.getDouble(columnName);
		return rs.wasNull() ? null : val;
	}	

}
