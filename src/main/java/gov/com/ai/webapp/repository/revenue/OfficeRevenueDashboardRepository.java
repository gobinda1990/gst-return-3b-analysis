package gov.com.ai.webapp.repository.revenue;

import gov.com.ai.webapp.model.revenue.OfficeRevenueDashboardResponse;
import gov.com.ai.webapp.model.revenue.OfficeRevenueRowResponse;
import gov.com.ai.webapp.model.revenue.OfficeRevenueTrendResponse;
import gov.com.ai.webapp.model.revenue.OptionDto;
import gov.com.ai.webapp.model.revenue.PageResponse;
import gov.com.ai.webapp.model.revenue.RevenueDashboardFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Oracle queries for the office revenue dashboard. Every filter value is a bind variable; nothing from the
 * request is concatenated into SQL.
 *
 * <p>Failures surface as Spring {@code DataAccessException}s and are mapped by the controller advice.
 */
@Slf4j
@Repository
public class OfficeRevenueDashboardRepository {

	/** Rows fetched per round trip while streaming the export (Oracle's default of 10 is far too small). */
	private static final int EXPORT_FETCH_SIZE = 1_000;

	private static final String TABLE = "GST_3B_OFFICE_MONTHLY_REVENUE";

	private static final String COLS = "r.RET_PERIOD,r.PERIOD_DATE,r.ST_JURI,r.OFFICE_NAME,r.FILED_GSTINS,r.TAXABLE_VALUE,r.OUTPUT_TAX,r.IGST,r.CGST,r.SGST,r.CESS,r.ELIGIBLE_ITC,r.UTILIZED_ITC,r.CASH_TAX_PAID,r.MOM_OUTPUT_GROWTH,r.YOY_OUTPUT_GROWTH,r.MOM_CASH_GROWTH,r.YOY_CASH_GROWTH,r.AVG_OUTPUT_TAX_3M,r.AVG_OUTPUT_TAX_6M,r.AVG_OUTPUT_TAX_12M,r.AVG_CASH_TAX_3M,r.AVG_CASH_TAX_6M,r.AVG_CASH_TAX_12M,r.GROWTH_TREND,r.GROWTH_STATUS";

	private static final String ORDER = " ORDER BY NVL(r.OUTPUT_TAX,0) DESC, r.ST_JURI ";

	private static final String JURI_QUERY = "SELECT JURISDICTION_CODE, MERGED_JURISDICTION FROM gst_master_juri_new "
			+ "WHERE charge_cd_vat = :officeId ORDER BY JURISDICTION_CODE";

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

	private final NamedParameterJdbcTemplate jdbc;

	/** Same DataSource, but with a large fetch size so the export streams in few round trips. */
	private final NamedParameterJdbcTemplate exportJdbc;

	public OfficeRevenueDashboardRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;

		DataSource ds = Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource(), "DataSource is required");
		JdbcTemplate streaming = new JdbcTemplate(ds);
		streaming.setFetchSize(EXPORT_FETCH_SIZE);
		this.exportJdbc = new NamedParameterJdbcTemplate(streaming);
	}

	// ---------------------------------------------------------------- filter

	private String where(RevenueDashboardFilter f, MapSqlParameterSource p) {
		StringBuilder w = new StringBuilder(" WHERE r.RET_PERIOD = :period ");
		p.addValue("period", f.retPeriod());

		if (txt(f.office())) {
			w.append(" AND r.ST_JURI = :office ");
			p.addValue("office", f.office());
		}
		if (txt(f.growthStatus())) {
			w.append(" AND r.GROWTH_STATUS = :status ");
			p.addValue("status", f.growthStatus());
		}
		if (txt(f.search())) {
			// ESCAPE takes exactly ONE character. The old literal '\\\\' reached Oracle as '\\' (two characters),
			// which fails with ORA-01425 and broke every search.
			w.append(" AND (UPPER(r.ST_JURI) LIKE :q ESCAPE '\\' OR UPPER(r.OFFICE_NAME) LIKE :q ESCAPE '\\') ");
			p.addValue("q", "%" + escape(f.search().toUpperCase(Locale.ROOT)) + "%");
		}
		return w.toString();
	}

	// ---------------------------------------------------------------- queries

	public OfficeRevenueDashboardResponse summary(RevenueDashboardFilter f) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		String sql = "SELECT COUNT(*) TOTAL_OFFICES, NVL(SUM(r.FILED_GSTINS),0) FILED_GSTINS, NVL(SUM(r.TAXABLE_VALUE),0) TAXABLE_VALUE, "
				+ "NVL(SUM(r.OUTPUT_TAX),0) OUTPUT_TAX, NVL(SUM(r.IGST),0) IGST, NVL(SUM(r.CGST),0) CGST, NVL(SUM(r.SGST),0) SGST, "
				+ "NVL(SUM(r.CESS),0) CESS, NVL(SUM(r.ELIGIBLE_ITC),0) ELIGIBLE_ITC, NVL(SUM(r.UTILIZED_ITC),0) UTILIZED_ITC, "
				+ "NVL(SUM(r.CASH_TAX_PAID),0) CASH_TAX_PAID, AVG(r.MOM_OUTPUT_GROWTH) AVG_MOM, AVG(r.YOY_OUTPUT_GROWTH) AVG_YOY "
				+ "FROM " + TABLE + " r" + where(f, p);

		// an aggregate query always returns exactly one row
		return jdbc.queryForObject(sql, p,
				(rs, n) -> new OfficeRevenueDashboardResponse(rs.getLong("TOTAL_OFFICES"), rs.getLong("FILED_GSTINS"),
						bd(rs, "TAXABLE_VALUE"), bd(rs, "OUTPUT_TAX"), bd(rs, "IGST"), bd(rs, "CGST"), bd(rs, "SGST"),
						bd(rs, "CESS"), bd(rs, "ELIGIBLE_ITC"), bd(rs, "UTILIZED_ITC"), bd(rs, "CASH_TAX_PAID"),
						rs.getBigDecimal("AVG_MOM"), rs.getBigDecimal("AVG_YOY")));
	}

	public PageResponse<OfficeRevenueRowResponse> page(RevenueDashboardFilter f) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		String w = where(f, p);

		Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE + " r" + w, p, Long.class);
		long count = total == null ? 0L : total;

		long lo = (long) f.page() * f.size();

		// past the last row: skip the second query
		if (lo >= count) {
			return PageResponse.of(List.of(), f.page(), f.size(), count);
		}

		p.addValue("lo", lo).addValue("hi", lo + f.size());

		String sql = "SELECT * FROM (SELECT q.*, ROWNUM rn FROM (SELECT " + COLS + " FROM " + TABLE + " r" + w + ORDER
				+ ") q WHERE ROWNUM <= :hi) WHERE rn > :lo";

		List<OfficeRevenueRowResponse> rows = jdbc.query(sql, p, ROW);

		return PageResponse.of(rows, f.page(), f.size(), count);
	}

	public List<OfficeRevenueTrendResponse> trend(String period, String office, int months) {
		MapSqlParameterSource x = new MapSqlParameterSource().addValue("p", period).addValue("m", months);

		String off = "";
		if (txt(office)) {
			off = " AND ST_JURI = :o ";
			x.addValue("o", office);
		}

		String sql = "SELECT * FROM (SELECT RET_PERIOD, PERIOD_DATE, SUM(OUTPUT_TAX) OUTPUT_TAX, SUM(CASH_TAX_PAID) CASH_TAX_PAID, "
				+ "SUM(TAXABLE_VALUE) TAXABLE_VALUE FROM " + TABLE
				+ " WHERE PERIOD_DATE <= (SELECT MAX(PERIOD_DATE) FROM " + TABLE + " WHERE RET_PERIOD = :p)" + off
				+ " GROUP BY RET_PERIOD, PERIOD_DATE ORDER BY PERIOD_DATE DESC) WHERE ROWNUM <= :m";

		// copy: do not rely on the template returning a mutable list
		List<OfficeRevenueTrendResponse> rows = new ArrayList<>(jdbc.query(sql, x,
				(rs, n) -> new OfficeRevenueTrendResponse(rs.getString("RET_PERIOD"), ld(rs.getDate("PERIOD_DATE")),
						bd(rs, "OUTPUT_TAX"), bd(rs, "CASH_TAX_PAID"), bd(rs, "TAXABLE_VALUE"))));

		Collections.reverse(rows); // oldest first for the chart

		return rows;
	}

	public List<OptionDto> periods() {
		return jdbc.query("SELECT RET_PERIOD, MAX(PERIOD_DATE) D FROM " + TABLE + " GROUP BY RET_PERIOD ORDER BY D DESC",
				(rs, n) -> new OptionDto(rs.getString("RET_PERIOD"), rs.getString("RET_PERIOD")));
	}

	public List<OptionDto> offices(String period) {
		String sql = "SELECT ST_JURI, OFFICE_NAME FROM " + TABLE + " WHERE RET_PERIOD = :p ORDER BY ST_JURI";

		return jdbc.query(sql, new MapSqlParameterSource("p", period),
				(rs, n) -> new OptionDto(rs.getString("ST_JURI"), rs.getString("OFFICE_NAME")));
	}

	public List<OptionDto> findChargeCdOffices(String officeId) {
		long start = System.nanoTime();

		List<OptionDto> result = jdbc.query(JURI_QUERY, new MapSqlParameterSource("officeId", officeId),
				(rs, n) -> new OptionDto(rs.getString("JURISDICTION_CODE"), rs.getString("MERGED_JURISDICTION")));

		if (log.isDebugEnabled()) {
			log.debug("Loaded charge-code offices count={} elapsedMs={}", result.size(),
					(System.nanoTime() - start) / 1_000_000);
		}

		return result;
	}

	/**
	 * Streams every matching row to {@code c} without holding them in memory. If {@code c} throws (for example
	 * the client went away), the exception propagates and Spring closes the cursor.
	 */
	public long streamExport(RevenueDashboardFilter f, Consumer<OfficeRevenueRowResponse> c) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		String sql = "SELECT " + COLS + " FROM " + TABLE + " r" + where(f, p) + ORDER;

		AtomicLong n = new AtomicLong();

		exportJdbc.query(sql, p, rs -> {
			c.accept(ROW.mapRow(rs, (int) Math.min(n.get(), Integer.MAX_VALUE)));
			n.incrementAndGet();
		});

		return n.get();
	}

	// ---------------------------------------------------------------- helpers

	private static boolean txt(String s) {
		return s != null && !s.isBlank();
	}

	/** Escapes LIKE wildcards; pairs with {@code ESCAPE '\'} in {@link #where}. */
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
}