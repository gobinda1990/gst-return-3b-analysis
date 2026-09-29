package gov.com.ai.webapp.repository.revenue;


import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import gov.com.ai.webapp.model.revenue.OfficeRevenueDashboardResponse;
import gov.com.ai.webapp.model.revenue.OfficeRevenueRowResponse;
import gov.com.ai.webapp.model.revenue.OfficeRevenueTrendResponse;
import gov.com.ai.webapp.model.revenue.OptionDto;
import gov.com.ai.webapp.model.revenue.PageResponse;
import gov.com.ai.webapp.model.revenue.RevenueDashboardFilter;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Repository
@RequiredArgsConstructor
public class OfficeRevenueDashboardRepository {
	private final NamedParameterJdbcTemplate jdbc;

	private String where(RevenueDashboardFilter f, MapSqlParameterSource p) {
		StringBuilder w = new StringBuilder(" WHERE r.RET_PERIOD=:period ");
		p.addValue("period", f.retPeriod());
		if (txt(f.office())) {
			w.append(" AND r.ST_JURI=:office ");
			p.addValue("office", f.office());
		}
		if (txt(f.growthStatus())) {
			w.append(" AND r.GROWTH_STATUS=:status ");
			p.addValue("status", f.growthStatus());
		}
		if (txt(f.search())) {
			w.append(" AND (UPPER(r.ST_JURI) LIKE :q ESCAPE '\\\\' OR UPPER(r.OFFICE_NAME) LIKE :q ESCAPE '\\\\') ");
			p.addValue("q", "%" + escape(f.search().toUpperCase(Locale.ROOT)) + "%");
		}
		return w.toString();
	}

	private static final String COLS = "r.RET_PERIOD,r.PERIOD_DATE,r.ST_JURI,r.OFFICE_NAME,r.FILED_GSTINS,r.TAXABLE_VALUE,r.OUTPUT_TAX,r.IGST,r.CGST,r.SGST,r.CESS,r.ELIGIBLE_ITC,r.UTILIZED_ITC,r.CASH_TAX_PAID,r.MOM_OUTPUT_GROWTH,r.YOY_OUTPUT_GROWTH,r.MOM_CASH_GROWTH,r.YOY_CASH_GROWTH,r.AVG_OUTPUT_TAX_3M,r.AVG_OUTPUT_TAX_6M,r.AVG_OUTPUT_TAX_12M,r.AVG_CASH_TAX_3M,r.AVG_CASH_TAX_6M,r.AVG_CASH_TAX_12M,r.GROWTH_TREND,r.GROWTH_STATUS";

	public OfficeRevenueDashboardResponse summary(RevenueDashboardFilter f) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		String sql = "SELECT COUNT(*) TOTAL_OFFICES,NVL(SUM(FILED_GSTINS),0) FILED_GSTINS,NVL(SUM(TAXABLE_VALUE),0) TAXABLE_VALUE,NVL(SUM(OUTPUT_TAX),0) OUTPUT_TAX,NVL(SUM(IGST),0) IGST,NVL(SUM(CGST),0) CGST,NVL(SUM(SGST),0) SGST,NVL(SUM(CESS),0) CESS,NVL(SUM(ELIGIBLE_ITC),0) ELIGIBLE_ITC,NVL(SUM(UTILIZED_ITC),0) UTILIZED_ITC,NVL(SUM(CASH_TAX_PAID),0) CASH_TAX_PAID,AVG(MOM_OUTPUT_GROWTH) AVG_MOM,AVG(YOY_OUTPUT_GROWTH) AVG_YOY FROM GST_3B_OFFICE_MONTHLY_REVENUE r"
				+ where(f, p);
		return jdbc.queryForObject(sql, p,
				(rs, n) -> new OfficeRevenueDashboardResponse(rs.getLong("TOTAL_OFFICES"), rs.getLong("FILED_GSTINS"),
						bd(rs, "TAXABLE_VALUE"), bd(rs, "OUTPUT_TAX"), bd(rs, "IGST"), bd(rs, "CGST"), bd(rs, "SGST"),
						bd(rs, "CESS"), bd(rs, "ELIGIBLE_ITC"), bd(rs, "UTILIZED_ITC"), bd(rs, "CASH_TAX_PAID"),
						rs.getBigDecimal("AVG_MOM"), rs.getBigDecimal("AVG_YOY")));
	}

	public PageResponse<OfficeRevenueRowResponse> page(RevenueDashboardFilter f) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		String w = where(f, p);
		Long total = jdbc.queryForObject("SELECT COUNT(*) FROM GST_3B_OFFICE_MONTHLY_REVENUE r" + w, p, Long.class);
		int lo = f.page() * f.size(), hi = lo + f.size();
		p.addValue("lo", lo).addValue("hi", hi);
		String sql = "SELECT * FROM (SELECT q.*,ROWNUM rn FROM (SELECT " + COLS
				+ " FROM GST_3B_OFFICE_MONTHLY_REVENUE r" + w
				+ " ORDER BY NVL(r.OUTPUT_TAX,0) DESC,r.ST_JURI) q WHERE ROWNUM<=:hi) WHERE rn>:lo";
		List<OfficeRevenueRowResponse> rows = jdbc.query(sql, p, ROW);
		return PageResponse.of(rows, f.page(), f.size(), total == null ? 0 : total);
	}

	public List<OfficeRevenueTrendResponse> trend(String p, String office, int months) {
		MapSqlParameterSource x = new MapSqlParameterSource().addValue("p", p).addValue("m", months);
		String off = "";
		if (txt(office)) {
			off = " AND ST_JURI=:o ";
			x.addValue("o", office);
		}
		String sql = "SELECT * FROM (SELECT RET_PERIOD,PERIOD_DATE,SUM(OUTPUT_TAX) OUTPUT_TAX,SUM(CASH_TAX_PAID) CASH_TAX_PAID,SUM(TAXABLE_VALUE) TAXABLE_VALUE FROM GST_3B_OFFICE_MONTHLY_REVENUE WHERE PERIOD_DATE<=(SELECT MAX(PERIOD_DATE) FROM GST_3B_OFFICE_MONTHLY_REVENUE WHERE RET_PERIOD=:p)"
				+ off + " GROUP BY RET_PERIOD,PERIOD_DATE ORDER BY PERIOD_DATE DESC) WHERE ROWNUM<=:m";
		List<OfficeRevenueTrendResponse> a = jdbc.query(sql, x,
				(rs, n) -> new OfficeRevenueTrendResponse(rs.getString("RET_PERIOD"), ld(rs.getDate("PERIOD_DATE")),
						bd(rs, "OUTPUT_TAX"), bd(rs, "CASH_TAX_PAID"), bd(rs, "TAXABLE_VALUE")));
		Collections.reverse(a);
		return a;
	}

	public List<OptionDto> periods() {
		return jdbc.query(
				"SELECT RET_PERIOD,MAX(PERIOD_DATE) D FROM GST_3B_OFFICE_MONTHLY_REVENUE GROUP BY RET_PERIOD ORDER BY D DESC",
				(rs, n) -> new OptionDto(rs.getString("RET_PERIOD"), rs.getString("RET_PERIOD")));
	}

	public List<OptionDto> offices(String p) {
	    String sql = " SELECT ST_JURI, OFFICE_NAME FROM GST_3B_OFFICE_MONTHLY_REVENUE  WHERE  "
	    		+ " RET_PERIOD = :p  ORDER BY ST_JURI ";

	    return jdbc.query(
	            sql,
	            new MapSqlParameterSource("p", p),
	            (rs, n) -> new OptionDto(
	                    rs.getString("ST_JURI"),
	                    rs.getString("OFFICE_NAME"))
	    );
	}

	public long streamExport(RevenueDashboardFilter f, Consumer<OfficeRevenueRowResponse> c) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		String sql = "SELECT " + COLS + " FROM GST_3B_OFFICE_MONTHLY_REVENUE r" + where(f, p)
				+ " ORDER BY NVL(r.OUTPUT_TAX,0) DESC,r.ST_JURI ";
		AtomicLong n = new AtomicLong();
		jdbc.query(sql, p, rs -> {
			c.accept(ROW.mapRow(rs, (int) Math.min(n.get(), Integer.MAX_VALUE)));
			n.incrementAndGet();
		});
		return n.get();
	}

	private static boolean txt(String s) {
		return s != null && !s.isBlank();
	}

	private static String escape(String s) {
		return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static BigDecimal bd(ResultSet r, String c) throws SQLException {
		BigDecimal v = r.getBigDecimal(c);
		return v == null ? BigDecimal.ZERO : v;
	}

	private static LocalDate ld(java.sql.Date d) {
		return d == null ? null : d.toLocalDate();
	}

	private static final RowMapper<OfficeRevenueRowResponse> ROW = (r, n) -> new OfficeRevenueRowResponse(
			r.getString("RET_PERIOD"), ld(r.getDate("PERIOD_DATE")), r.getString("ST_JURI"), r.getString("OFFICE_NAME"),
			r.getLong("FILED_GSTINS"), bd(r, "TAXABLE_VALUE"), bd(r, "OUTPUT_TAX"), bd(r, "IGST"), bd(r, "CGST"),
			bd(r, "SGST"), bd(r, "CESS"), bd(r, "ELIGIBLE_ITC"), bd(r, "UTILIZED_ITC"), bd(r, "CASH_TAX_PAID"),
			r.getBigDecimal("MOM_OUTPUT_GROWTH"), r.getBigDecimal("YOY_OUTPUT_GROWTH"),
			r.getBigDecimal("MOM_CASH_GROWTH"), r.getBigDecimal("YOY_CASH_GROWTH"),
			r.getBigDecimal("AVG_OUTPUT_TAX_3M"), r.getBigDecimal("AVG_OUTPUT_TAX_6M"),
			r.getBigDecimal("AVG_OUTPUT_TAX_12M"), r.getBigDecimal("AVG_CASH_TAX_3M"),
			r.getBigDecimal("AVG_CASH_TAX_6M"), r.getBigDecimal("AVG_CASH_TAX_12M"), r.getString("GROWTH_TREND"),
			r.getString("GROWTH_STATUS"));
}
