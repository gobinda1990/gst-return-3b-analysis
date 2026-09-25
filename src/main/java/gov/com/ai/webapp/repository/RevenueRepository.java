package gov.com.ai.webapp.repository;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;
import gov.com.ai.webapp.model.GstMonthlySummaryDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Repository
@RequiredArgsConstructor
@Slf4j
public class RevenueRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	/**
	 * Monthly GSTR-3B revenue aggregation.
	 *
	 * <p>
	 * Definitions:
	 * <ul>
	 * <li>total_normal_output_tax = normal outward tax only</li>
	 * <li>total_rcm_tax = RCM liability</li>
	 * <li>total_tax_liability = normal output tax + RCM tax</li>
	 * <li>total_cash_collection = actual recorded cash tax paid</li>
	 * <li>total_credit_utilized = actual ITC utilized</li>
	 * </ul>
	 *
	 * <p>
	 * RCM is deliberately kept separate from normal outward tax.
	 */
	private static final String BASE_SELECT = """
			SELECT
			    ret_period,

			    COUNT(DISTINCT gstin) AS total_taxpayers_filed,

			    /* Taxable value */
			    SUM(NVL(taxable_value, 0))
			        AS gross_taxable_value,

			    /* Normal outward tax */
			    SUM(NVL(output_igst, 0))
			        AS total_igst_collected,

			    SUM(NVL(output_cgst, 0))
			        AS total_cgst_collected,

			    SUM(NVL(output_sgst, 0))
			        AS total_sgst_collected,

			    SUM(NVL(output_cess, 0))
			        AS total_cess_collected,

			    SUM(NVL(total_output_tax, 0))
			        AS total_normal_output_tax,

			    /* Reverse-charge liability */
			    SUM(NVL(rcm_total_tax, 0))
			        AS total_rcm_tax,

			    /* Combined liability */
			    SUM(
			        NVL(total_output_tax, 0)
			        + NVL(rcm_total_tax, 0)
			    ) AS total_tax_liability,

			    /* Actual cash tax paid */
			    SUM(NVL(cash_tax_paid, 0))
			        AS total_cash_collection,

			    /* Actual ITC utilized */
			    SUM(NVL(utilized_itc, 0))
			        AS total_credit_utilized,

			    /* Reported interest and late fee */
			    SUM(NVL(late_fee_paid, 0))
			        AS total_late_fee_collected,

			    SUM(NVL(interest_paid, 0))
			        AS total_interest_collected,

			    /*
			     * Cash realization:
			     *
			     * actual cash tax paid
			     * ----------------------------- x 100
			     * normal output tax + RCM tax
			     */
			    CASE
			        WHEN SUM(
			            NVL(total_output_tax, 0)
			            + NVL(rcm_total_tax, 0)
			        ) = 0
			        THEN 0
			        ELSE ROUND(
			            (
			                SUM(NVL(cash_tax_paid, 0))
			                /
			                SUM(
			                    NVL(total_output_tax, 0)
			                    + NVL(rcm_total_tax, 0)
			                )
			            ) * 100,
			            2
			        )
			    END AS cash_realization_pct,

			    /*
			     * ITC utilization:
			     *
			     * utilized ITC
			     * ----------------------------- x 100
			     * normal outward output tax
			     *
			     * RCM is intentionally excluded from this denominator.
			     */
			    CASE
			        WHEN SUM(NVL(total_output_tax, 0)) = 0
			        THEN 0
			        ELSE ROUND(
			            (
			                SUM(NVL(utilized_itc, 0))
			                /
			                SUM(NVL(total_output_tax, 0))
			            ) * 100,
			            2
			        )
			    END AS itc_utilization_pct

			FROM gst_ret_3b_summary
			""";

	private static final String GROUP_ORDER = """
			GROUP BY ret_period
			ORDER BY TO_DATE(ret_period, 'MMYYYY') ASC
			""";

	/**
	 * Fetch monthly GSTR-3B revenue summary.
	 *
	 * @param periods optional list of return periods in MMYYYY format. Null or
	 *                empty means all periods.
	 * @return monthly aggregated summaries
	 */
	public List<GstMonthlySummaryDto> fetchMonthlyRevenueSummary(List<String> periods) {

		List<String> normalizedPeriods = normalizePeriods(periods);

		MapSqlParameterSource parameters = new MapSqlParameterSource();

		final String sql;

		if (normalizedPeriods.isEmpty()) {

			sql = BASE_SELECT + GROUP_ORDER;

			log.debug("Fetching GSTR-3B monthly revenue summary for all periods");

		} else {

			sql = BASE_SELECT + "\nWHERE ret_period IN (:periods)\n" + GROUP_ORDER;

			parameters.addValue("periods", normalizedPeriods);

			log.debug("Fetching GSTR-3B monthly revenue summary for {} period(s)", normalizedPeriods.size());
		}

		try {

			List<GstMonthlySummaryDto> result = jdbcTemplate.query(sql, parameters, new GstMonthlySummaryRowMapper());

			log.debug("GSTR-3B monthly revenue summary completed: rows={}", result.size());

			return result;

		} catch (DataAccessException ex) {

			log.error("Database error while fetching GSTR-3B monthly revenue summary: periodsCount={}",
					normalizedPeriods.size(), ex);

			throw ex;
		}
	}

	/**
	 * Normalize and validate requested return periods.
	 *
	 * <p>
	 * Expected format: MMYYYY <br>
	 * Examples: 032026, 042026, 122025
	 */
	private List<String> normalizePeriods(List<String> periods) {

		if (periods == null || periods.isEmpty()) {
			return Collections.emptyList();
		}

		List<String> normalized = periods.stream().filter(Objects::nonNull).map(String::trim)
				.filter(StringUtils::hasText).distinct().toList();

		List<String> invalidPeriods = normalized.stream().filter(period -> !isValidReturnPeriod(period)).toList();

		if (!invalidPeriods.isEmpty()) {

			log.warn("Invalid GSTR-3B return period(s) supplied: {}", invalidPeriods);

			throw new IllegalArgumentException(
					"Invalid GSTR-3B return period(s). " + "Expected MMYYYY format: " + invalidPeriods);
		}

		return normalized;
	}

	/**
	 * Validate MMYYYY return-period format.
	 */
	private boolean isValidReturnPeriod(String period) {

		if (period == null || period.length() != 6 || !period.matches("\\d{6}")) {

			return false;
		}

		try {

			int month = Integer.parseInt(period.substring(0, 2));

			int year = Integer.parseInt(period.substring(2, 6));

			return month >= 1 && month <= 12 && year >= 2000 && year <= 9999;

		} catch (NumberFormatException ex) {

			return false;
		}
	}
}