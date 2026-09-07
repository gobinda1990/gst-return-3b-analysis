package gov.com.ai.webapp.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import gov.com.ai.webapp.model.GstMonthlySummaryDto;

import java.util.List;

@Repository
public class GstRet3bSummaryRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	// Inject NamedParameterJdbcTemplate via Constructor
	public GstRet3bSummaryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<GstMonthlySummaryDto> fetchMonthlyRevenueSummary(List<String> periods) {
		String sql = """
				           SELECT
				    ret_period,
				    COUNT(DISTINCT gstin) AS total_taxpayers_filed,

				    -- Taxable Value Breakdown
				    SUM(taxable_value) AS gross_taxable_value,

				    -- Tax Heads Collection (Output Liability)
				    SUM(output_igst) AS total_igst_collected,
				    SUM(output_cgst) AS total_cgst_collected,
				    SUM(output_sgst) AS total_sgst_collected,
				    SUM(output_cess) AS total_cess_collected,

				    -- Combined Gross Output Tax Realization
				    SUM(total_output_tax) AS total_gross_revenue,

				    -- Settlement Mode Breakdown
				    SUM(total_output_tax - utilized_itc) AS total_cash_collection,
				    SUM(utilized_itc) AS total_credit_utilized,

				    -- Realization Ratios
				    ROUND((SUM(total_output_tax - utilized_itc) / NULLIF(SUM(total_output_tax), 0)) * 100, 2) AS cash_realization_pct,
				    ROUND((SUM(utilized_itc) / NULLIF(SUM(total_output_tax), 0)) * 100, 2) AS itc_utilization_pct

				FROM gst_ret_3b_summary

				WHERE ret_period IN (:periods)

				GROUP BY ret_period

				ORDER BY TO_DATE(ret_period, 'MMYYYY') ASC
				            """;

		MapSqlParameterSource parameters = new MapSqlParameterSource();
		parameters.addValue("periods", periods);

		return jdbcTemplate.query(sql, parameters, new GstMonthlySummaryRowMapper());
	}
}
