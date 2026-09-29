package gov.com.ai.webapp.repository.revenue;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import gov.com.ai.webapp.config.RevenueProperties;
import gov.com.ai.webapp.exception.RevenueBatchException;
import gov.com.ai.webapp.model.dto.OfficeMonthlyRevenue;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

@Slf4j
@Repository
public class OfficeRevenueBatchRepository {
	private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("MMuuuu");
	private final NamedParameterJdbcTemplate jdbc;
	private final String aggregateSql;

	public OfficeRevenueBatchRepository(NamedParameterJdbcTemplate jdbc, RevenueProperties p) {
		this.jdbc = jdbc;
		String col = p.getMasterOfficeNameColumn() == null ? "OFFICE_NAME"
				: p.getMasterOfficeNameColumn().trim().toUpperCase(Locale.ROOT);
		if (!col.matches("[A-Z][A-Z0-9_]{0,29}"))
			throw new IllegalArgumentException("Invalid gst_master_jurisdiction office name column");
		this.aggregateSql = """
				SELECT s.RET_PERIOD,r.ST_JURI,MAX(j.%s) OFFICE_NAME,COUNT(DISTINCT s.GSTIN) FILED_GSTINS,
				NVL(SUM(NVL(s.TAXABLE_VALUE,0)),0) TAXABLE_VALUE,NVL(SUM(NVL(s.TOTAL_OUTPUT_TAX,0)),0) OUTPUT_TAX,
				NVL(SUM(NVL(s.OUTPUT_IGST,0)),0) IGST,NVL(SUM(NVL(s.OUTPUT_CGST,0)),0) CGST,NVL(SUM(NVL(s.OUTPUT_SGST,0)),0) SGST,NVL(SUM(NVL(s.OUTPUT_CESS,0)),0) CESS,
				NVL(SUM(NVL(s.ELIGIBLE_ITC,0)),0) ELIGIBLE_ITC,NVL(SUM(NVL(s.UTILIZED_ITC,0)),0) UTILIZED_ITC,NVL(SUM(NVL(s.CASH_TAX_PAID,0)),0) CASH_TAX_PAID
				FROM GST_RET_3B_SUMMARY s
				JOIN (SELECT GSTIN,MAX(ST_JURI) ST_JURI FROM GST_DEALER_MASTER_WBCOMTAX WHERE GSTIN IS NOT NULL AND ST_JURI IS NOT NULL GROUP BY GSTIN) r ON r.GSTIN=s.GSTIN
				JOIN (SELECT JURISDICTION_CODE,MAX(%s) %s FROM gst_master_jurisdiction WHERE JURISDICTION_CODE IS NOT NULL GROUP BY JURISDICTION_CODE) j ON j.JURISDICTION_CODE=r.ST_JURI
				WHERE s.RET_PERIOD=:p AND s.GSTIN IS NOT NULL GROUP BY s.RET_PERIOD,r.ST_JURI ORDER BY r.ST_JURI
				"""
				.formatted(col, col, col);
	}

	private static final String MERGE = " MERGE INTO GST_3B_OFFICE_MONTHLY_REVENUE t USING ( "
			+ " SELECT :p RET_PERIOD,:d PERIOD_DATE,:j ST_JURI,:n OFFICE_NAME,:f FILED_GSTINS,:tv TAXABLE_VALUE, "
			+ " :ot OUTPUT_TAX,:i IGST,:c CGST,:s SGST,:ce CESS,:ei ELIGIBLE_ITC,:ui UTILIZED_ITC,:cash CASH_TAX_PAID  "
			+ " FROM DUAL) x ON(t.RET_PERIOD=x.RET_PERIOD AND t.ST_JURI=x.ST_JURI) " + " WHEN MATCHED "
			+ " THEN UPDATE SET "
			+ " t.PERIOD_DATE=x.PERIOD_DATE,t.OFFICE_NAME=x.OFFICE_NAME,t.FILED_GSTINS=x.FILED_GSTINS, "
			+ " t.TAXABLE_VALUE=x.TAXABLE_VALUE,t.OUTPUT_TAX=x.OUTPUT_TAX,t.IGST=x.IGST,t.CGST=x.CGST,t.SGST=x.SGST,"
			+ " t.CESS=x.CESS,t.ELIGIBLE_ITC=x.ELIGIBLE_ITC,t.UTILIZED_ITC=x.UTILIZED_ITC,t.CASH_TAX_PAID=x.CASH_TAX_PAID, "
			+ " t.UPDATED_AT=SYSTIMESTAMP WHEN NOT MATCHED THEN INSERT(RET_PERIOD,PERIOD_DATE,ST_JURI,OFFICE_NAME, "
			+ " FILED_GSTINS,TAXABLE_VALUE,OUTPUT_TAX,IGST,CGST,SGST,CESS,ELIGIBLE_ITC,UTILIZED_ITC,CASH_TAX_PAID, "
			+ " CREATED_AT,UPDATED_AT) VALUES(x.RET_PERIOD,x.PERIOD_DATE,x.ST_JURI,x.OFFICE_NAME,x.FILED_GSTINS, "
			+ " x.TAXABLE_VALUE,x.OUTPUT_TAX,x.IGST,x.CGST,x.SGST,x.CESS,x.ELIGIBLE_ITC,x.UTILIZED_ITC,x.CASH_TAX_PAID, "
			+ " SYSTIMESTAMP,SYSTIMESTAMP)";

	private static final String HIST = " SELECT * FROM GST_3B_OFFICE_MONTHLY_REVENUE WHERE ST_JURI=:j AND  "
			+ " PERIOD_DATE BETWEEN ADD_MONTHS(:d,-12) AND :d ORDER BY PERIOD_DATE";

	private static final String EXACT = " SELECT * FROM GST_3B_OFFICE_MONTHLY_REVENUE WHERE RET_PERIOD=:p AND ST_JURI=:j";

	private static final String UPDATE = " UPDATE GST_3B_OFFICE_MONTHLY_REVENUE SET MOM_OUTPUT_GROWTH=:mo,"
			+ " YOY_OUTPUT_GROWTH=:yo,MOM_CASH_GROWTH=:mc,YOY_CASH_GROWTH=:yc,AVG_OUTPUT_TAX_3M=:o3, "
			+ " AVG_OUTPUT_TAX_6M=:o6,AVG_OUTPUT_TAX_12M=:o12,AVG_CASH_TAX_3M=:c3,AVG_CASH_TAX_6M=:c6, "
			+ " AVG_CASH_TAX_12M=:c12,GROWTH_TREND=:gt,GROWTH_STATUS=:gs,UPDATED_AT=SYSTIMESTAMP  "
			+ " WHERE RET_PERIOD=:p AND ST_JURI=:j";

	public long countSourceRows(String p) {
		return q("SELECT COUNT(*) FROM GST_RET_3B_SUMMARY WHERE RET_PERIOD=:p", p);
	}

	public long countSourceGstins(String p) {
		return q("SELECT COUNT(DISTINCT GSTIN) FROM GST_RET_3B_SUMMARY WHERE RET_PERIOD=:p AND GSTIN IS NOT NULL", p);
	}

	public long countUnmappedRegistrations(String p) {
		return q(
				"SELECT COUNT(DISTINCT s.GSTIN) FROM GST_RET_3B_SUMMARY s LEFT JOIN GST_DEALER_MASTER_WBCOMTAX r ON r.GSTIN=s.GSTIN WHERE s.RET_PERIOD=:p AND s.GSTIN IS NOT NULL AND (r.GSTIN IS NULL OR r.ST_JURI IS NULL)",
				p);
	}

	public long countUnmappedJurisdictions(String p) {
		return q(
				"SELECT COUNT(DISTINCT s.GSTIN) FROM GST_RET_3B_SUMMARY s JOIN GST_DEALER_MASTER_WBCOMTAX r ON r.GSTIN=s.GSTIN LEFT JOIN gst_master_jurisdiction j ON j.JURISDICTION_CODE=r.ST_JURI WHERE s.RET_PERIOD=:p AND r.ST_JURI IS NOT NULL AND j.JURISDICTION_CODE IS NULL",
				p);
	}

	public long countDuplicateMappings(String p) {
		return q(
				"SELECT COUNT(*) FROM (SELECT r.GSTIN FROM GST_DEALER_MASTER_WBCOMTAX r JOIN (SELECT DISTINCT GSTIN FROM GST_RET_3B_SUMMARY WHERE RET_PERIOD=:p AND GSTIN IS NOT NULL) s ON s.GSTIN=r.GSTIN WHERE r.ST_JURI IS NOT NULL GROUP BY r.GSTIN HAVING COUNT(DISTINCT r.ST_JURI)>1)",
				p);
	}

	public List<OfficeMonthlyRevenue> aggregate(String p) {
		valid(p);
		return jdbc.query(aggregateSql, new MapSqlParameterSource("p", p), AGG);
	}

	public int merge(OfficeMonthlyRevenue r) {
		MapSqlParameterSource x = new MapSqlParameterSource().addValue("p", r.retPeriod())
				.addValue("d", java.sql.Date.valueOf(r.periodDate())).addValue("j", r.stJuri())
				.addValue("n", r.officeName()).addValue("f", r.filedGstins()).addValue("tv", z(r.taxableValue()))
				.addValue("ot", z(r.outputTax())).addValue("i", z(r.igst())).addValue("c", z(r.cgst()))
				.addValue("s", z(r.sgst())).addValue("ce", z(r.cess())).addValue("ei", z(r.eligibleItc()))
				.addValue("ui", z(r.utilizedItc())).addValue("cash", z(r.cashTaxPaid()));
		return jdbc.update(MERGE, x);
	}

	public int deleteStale(String p) {
		return jdbc.update(
				"DELETE FROM GST_3B_OFFICE_MONTHLY_REVENUE t WHERE t.RET_PERIOD=:p AND NOT EXISTS (SELECT 1 FROM GST_RET_3B_SUMMARY s JOIN GST_REG_DETAILS r ON r.GSTIN=s.GSTIN WHERE s.RET_PERIOD=:p AND r.ST_JURI=t.ST_JURI)",
				new MapSqlParameterSource("p", p));
	}

	public List<String> offices(String p) {
		return jdbc.query("SELECT ST_JURI FROM GST_3B_OFFICE_MONTHLY_REVENUE WHERE RET_PERIOD=:p ORDER BY ST_JURI",
				new MapSqlParameterSource("p", p), (rs, n) -> rs.getString(1));
	}

	public OfficeMonthlyRevenue exact(String j, String p) {
		List<OfficeMonthlyRevenue> x = jdbc.query(EXACT, new MapSqlParameterSource().addValue("j", j).addValue("p", p),
				FULL);
		return x.isEmpty() ? null : x.get(0);
	}

	public List<OfficeMonthlyRevenue> history(String j, String p) {
		LocalDate d = YearMonth.parse(p, F).atDay(1);
		return jdbc.query(HIST, new MapSqlParameterSource().addValue("j", j).addValue("d", java.sql.Date.valueOf(d)),
				FULL);
	}

	public int updateGrowth(String p, String j, BigDecimal mo, BigDecimal yo, BigDecimal mc, BigDecimal yc,
			BigDecimal o3, BigDecimal o6, BigDecimal o12, BigDecimal c3, BigDecimal c6, BigDecimal c12, String gt,
			String gs) {
		return jdbc.update(UPDATE,
				new MapSqlParameterSource().addValue("p", p).addValue("j", j).addValue("mo", mo).addValue("yo", yo)
						.addValue("mc", mc).addValue("yc", yc).addValue("o3", o3).addValue("o6", o6)
						.addValue("o12", o12).addValue("c3", c3).addValue("c6", c6).addValue("c12", c12)
						.addValue("gt", gt).addValue("gs", gs));
	}

	private long q(String s, String p) {
		valid(p);
		Long v = jdbc.queryForObject(s, new MapSqlParameterSource("p", p), Long.class);
		return v == null ? 0 : v;
	}

	private void valid(String p) {
		if (p == null || !p.matches("(0[1-9]|1[0-2])\\d{4}"))
			throw new RevenueBatchException("Invalid period: " + p);
	}

	private static BigDecimal z(BigDecimal v) {
		return v == null ? BigDecimal.ZERO : v;
	}

	private static BigDecimal bd(ResultSet r, String c) throws SQLException {
		return Optional.ofNullable(r.getBigDecimal(c)).orElse(BigDecimal.ZERO);
	}

	private static BigDecimal nbd(ResultSet r, String c) throws SQLException {
		return r.getBigDecimal(c);
	}

	private static LocalDate ld(java.sql.Date d) {
		return d == null ? null : d.toLocalDate();
	}

	private static final RowMapper<OfficeMonthlyRevenue> AGG = (r, n) -> new OfficeMonthlyRevenue(
			r.getString("RET_PERIOD"), YearMonth.parse(r.getString("RET_PERIOD"), F).atDay(1), r.getString("ST_JURI"),
			r.getString("OFFICE_NAME"), r.getLong("FILED_GSTINS"), bd(r, "TAXABLE_VALUE"), bd(r, "OUTPUT_TAX"),
			bd(r, "IGST"), bd(r, "CGST"), bd(r, "SGST"), bd(r, "CESS"), bd(r, "ELIGIBLE_ITC"), bd(r, "UTILIZED_ITC"),
			bd(r, "CASH_TAX_PAID"), null, null, null, null, null, null, null, null, null, null, null, null);
	private static final RowMapper<OfficeMonthlyRevenue> FULL = (r, n) -> new OfficeMonthlyRevenue(
			r.getString("RET_PERIOD"), ld(r.getDate("PERIOD_DATE")), r.getString("ST_JURI"), r.getString("OFFICE_NAME"),
			r.getLong("FILED_GSTINS"), bd(r, "TAXABLE_VALUE"), bd(r, "OUTPUT_TAX"), bd(r, "IGST"), bd(r, "CGST"),
			bd(r, "SGST"), bd(r, "CESS"), bd(r, "ELIGIBLE_ITC"), bd(r, "UTILIZED_ITC"), bd(r, "CASH_TAX_PAID"),
			nbd(r, "MOM_OUTPUT_GROWTH"), nbd(r, "YOY_OUTPUT_GROWTH"), nbd(r, "MOM_CASH_GROWTH"),
			nbd(r, "YOY_CASH_GROWTH"), nbd(r, "AVG_OUTPUT_TAX_3M"), nbd(r, "AVG_OUTPUT_TAX_6M"),
			nbd(r, "AVG_OUTPUT_TAX_12M"), nbd(r, "AVG_CASH_TAX_3M"), nbd(r, "AVG_CASH_TAX_6M"),
			nbd(r, "AVG_CASH_TAX_12M"), r.getString("GROWTH_TREND"), r.getString("GROWTH_STATUS"));
}
