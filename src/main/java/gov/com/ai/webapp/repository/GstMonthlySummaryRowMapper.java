package gov.com.ai.webapp.repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import gov.com.ai.webapp.model.GstMonthlySummaryDto;

/**
 * Maps one aggregated row returned by
 * {@link RevenueRepository#fetchMonthlyRevenueSummary(List)} to
 * {@link GstMonthlySummaryDto}.
 *
 * <p>
 * Column aliases must remain synchronized with the SELECT clause of
 * GstRet3bSummaryRepository.
 *
 * <p>
 * Production characteristics:
 * <ul>
 * <li>Null-safe monetary and percentage mapping.</li>
 * <li>Monetary values normalized to 2 decimal places.</li>
 * <li>Percentage values normalized to 2 decimal places.</li>
 * <li>SQLException is never swallowed.</li>
 * <li>Mapping failures include row number and return period.</li>
 * <li>No sensitive GSTIN-level data is logged.</li>
 * </ul>
 */
public final class GstMonthlySummaryRowMapper implements RowMapper<GstMonthlySummaryDto> {

	private static final Logger log = LoggerFactory.getLogger(GstMonthlySummaryRowMapper.class);

	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

	private static final int MONEY_SCALE = 2;
	private static final int PERCENTAGE_SCALE = 2;

	@Override
	public GstMonthlySummaryDto mapRow(ResultSet rs, int rowNum) throws SQLException {

		String retPeriod = null;

		try {
			retPeriod = stringValue(rs, "ret_period");

			long taxpayersFiled = longValue(rs, "total_taxpayers_filed");

			GstMonthlySummaryDto dto = new GstMonthlySummaryDto(retPeriod, taxpayersFiled,

					// Taxable value
					money(rs, "gross_taxable_value"),

					// Normal outward tax
					money(rs, "total_igst_collected"), money(rs, "total_cgst_collected"),
					money(rs, "total_sgst_collected"), money(rs, "total_cess_collected"),
					money(rs, "total_normal_output_tax"),

					// RCM
					money(rs, "total_rcm_tax"),

					// Combined liability
					money(rs, "total_tax_liability"),

					// Settlement
					money(rs, "total_cash_collection"), money(rs, "total_credit_utilized"),

					// Compliance-related collection
					money(rs, "total_late_fee_collected"), money(rs, "total_interest_collected"),

					// Ratios / percentages
					percentage(rs, "cash_realization_pct"), percentage(rs, "itc_utilization_pct"));

			if (log.isDebugEnabled()) {
				log.debug("Mapped GSTR-3B monthly summary: rowNum={}, retPeriod={}, taxpayersFiled={}", rowNum,
						retPeriod, taxpayersFiled);
			}

			return dto;

		} catch (SQLException ex) {

			log.error("Failed to map GSTR-3B monthly summary: rowNum={}, retPeriod={}, sqlState={}, errorCode={}",
					rowNum, retPeriod, ex.getSQLState(), ex.getErrorCode(), ex);

			throw ex;

		} catch (RuntimeException ex) {

			log.error("Unexpected error mapping GSTR-3B monthly summary: rowNum={}, retPeriod={}", rowNum, retPeriod,
					ex);

			throw ex;
		}
	}

	/**
	 * Reads a monetary database value.
	 *
	 * <p>
	 * Oracle NUMBER is mapped directly to BigDecimal. NULL is normalized to zero to
	 * keep the dashboard DTO deterministic.
	 */
	private static BigDecimal money(ResultSet rs, String column) throws SQLException {

		BigDecimal value = rs.getBigDecimal(column);

		if (value == null) {
			return ZERO;
		}

		return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
	}

	/**
	 * Reads a percentage value.
	 *
	 * <p>
	 * SQL already performs the percentage calculation. This method only normalizes
	 * precision and protects against NULL.
	 */
	private static BigDecimal percentage(ResultSet rs, String column) throws SQLException {

		BigDecimal value = rs.getBigDecimal(column);

		if (value == null) {
			return ZERO;
		}

		return value.setScale(PERCENTAGE_SCALE, RoundingMode.HALF_UP);
	}

	/**
	 * Safely reads a String column.
	 */
	private static String stringValue(ResultSet rs, String column) throws SQLException {

		String value = rs.getString(column);

		if (value == null) {
			return null;
		}

		String trimmed = value.trim();

		return trimmed.isEmpty() ? null : trimmed;
	}

	/**
	 * Safely reads a numeric count.
	 */
	private static long longValue(ResultSet rs, String column) throws SQLException {

		long value = rs.getLong(column);

		/*
		 * getLong() returns 0 for SQL NULL. For a taxpayer count this is an acceptable
		 * deterministic fallback.
		 */
		return rs.wasNull() ? 0L : value;
	}
}