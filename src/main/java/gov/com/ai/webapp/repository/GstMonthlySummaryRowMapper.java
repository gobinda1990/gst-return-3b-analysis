package gov.com.ai.webapp.repository;

import org.springframework.jdbc.core.RowMapper;

import gov.com.ai.webapp.model.GstMonthlySummaryDto;

import java.sql.ResultSet;
import java.sql.SQLException;

public class GstMonthlySummaryRowMapper implements RowMapper<GstMonthlySummaryDto> {

	@Override
	public GstMonthlySummaryDto mapRow(ResultSet rs, int rowNum) throws SQLException {
		return new GstMonthlySummaryDto(rs.getString("ret_period"), rs.getLong("total_taxpayers_filed"),
				rs.getBigDecimal("gross_taxable_value"), rs.getBigDecimal("total_igst_collected"),
				rs.getBigDecimal("total_cgst_collected"), rs.getBigDecimal("total_sgst_collected"),
				rs.getBigDecimal("total_cess_collected"), rs.getBigDecimal("total_gross_revenue"),
				rs.getBigDecimal("total_cash_collection"), rs.getBigDecimal("total_credit_utilized"),
				rs.getBigDecimal("cash_realization_pct"), rs.getBigDecimal("itc_utilization_pct"));
	}
}
