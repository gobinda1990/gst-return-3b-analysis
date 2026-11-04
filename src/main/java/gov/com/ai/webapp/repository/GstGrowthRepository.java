package gov.com.ai.webapp.repository;

import gov.com.ai.webapp.exception.GrowthDataAccessException;
import gov.com.ai.webapp.model.ReturnPeriodOptionDto;
import gov.com.ai.webapp.model.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Slf4j
@Repository
public class GstGrowthRepository {

	private static final int EXPORT_FETCH_SIZE = 1000;
	private static final int QUERY_TIMEOUT_SECONDS = 60;
	private static final int EXPORT_TIMEOUT_SECONDS = 600;
	private static final int MAX_PAGE_SIZE = 500;

	private static final Pattern PERIOD_PATTERN = Pattern.compile("(0[1-9]|1[0-2])\\d{4}");
	private static final Pattern GSTIN_PATTERN = Pattern.compile("[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][0-9A-Z]Z[0-9A-Z]");
	private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("ddMMyyyy");

	private static final String[] MONTHS = { "January", "February", "March", "April", "May", "June", "July", "August",
			"September", "October", "November", "December" };

	private final JdbcTemplate queryJdbc;

	private final JdbcTemplate exportJdbc;

	public GstGrowthRepository(JdbcTemplate base) {
		this.queryJdbc = derive(base, QUERY_TIMEOUT_SECONDS, 0);
		this.exportJdbc = derive(base, EXPORT_TIMEOUT_SECONDS, EXPORT_FETCH_SIZE);
	}

	private static JdbcTemplate derive(JdbcTemplate base, int timeoutSeconds, int fetchSize) {
		JdbcTemplate t = new JdbcTemplate(base.getDataSource());
		t.setExceptionTranslator(base.getExceptionTranslator());
		t.setQueryTimeout(timeoutSeconds);
		if (fetchSize > 0) {
			t.setFetchSize(fetchSize);
		}
		return t;
	}

	// SQL FRAGMENTS - joins are added only when the query really needs them
	private static final String RET_PRD_QUERY = " SELECT DISTINCT RET_PERIOD, PERIOD_DATE FROM GST_RET_3B_GROWTH  "
			+ " ORDER BY PERIOD_DATE DESC ";

	private static final String JURI_OFFICE_QUERY = "SELECT JURISDICTION_CODE, MERGED_JURISDICTION "
			+ "FROM gst_master_juri_new ORDER BY JURISDICTION_CODE ";

	private static final String JURI_QUERY = "SELECT JURISDICTION_CODE, MERGED_JURISDICTION "
			+ "FROM gst_master_juri_new WHERE charge_cd_vat = ? ORDER BY JURISDICTION_CODE";

	private static final String FROM_G = " FROM GST_RET_3B_GROWTH g ";

	private static final String JOIN_D = " LEFT JOIN GST_DEALER_MASTER_WBCOMTAX d ON d.GSTIN = g.GSTIN ";

	private static final String JOIN_J = " LEFT JOIN gst_master_jurisdiction j ON j.JURISDICTION_CODE = d.ST_JURI ";

	// Risk profile MUST join on GSTIN + RET_PERIOD.
	private static final String JOIN_R = " LEFT JOIN GST_RET_3B_RISK_PROFILE r ON r.GSTIN = g.GSTIN "
			+ "AND r.RET_PERIOD = g.RET_PERIOD ";

	private static final String ORDER_BY = " ORDER BY j.JURISDICTION_NAME NULLS LAST, d.TRADE_NAME NULLS LAST, g.GSTIN ";

	private static final String PAGE_COLUMNS = " SELECT g.GSTIN, d.TRADE_NAME, d.ST_JURI, j.JURISDICTION_NAME, "
			+ " g.RET_PERIOD, g.PERIOD_DATE, g.TAXABLE_VALUE, g.OUTPUT_TAX, g.ELIGIBLE_ITC, g.UTILIZED_ITC, "
			+ " g.CASH_TAX_PAID,"
//			+ " g.RCM_TOTAL_TAX,"
			+ " g.MOM_TAXABLE_GROWTH,"
			+ " g.MOM_OUTPUT_TAX_GROWTH,"
//			+ " g.MOM_ITC_GROWTH, "
//			+ " g.MOM_CASH_GROWTH,g.YOY_TAXABLE_GROWTH, "
			+ " g.YOY_OUTPUT_TAX_GROWTH, "
//			+ " g.YOY_ITC_GROWTH, g.YOY_CASH_GROWTH, "
			+ " g.GROWTH_TREND, r.RISK_SCORE, r.RISK_LEVEL ";

	private static final String SUMMARY_COLUMNS = "SELECT COUNT(*) TOTAL_GSTINS,"
			+ "  NVL(SUM(g.TAXABLE_VALUE), 0) TOTAL_TAXABLE, NVL(SUM(g.OUTPUT_TAX), 0) TOTAL_OUTPUT, "
			+ "  NVL(SUM(g.ELIGIBLE_ITC), 0) TOTAL_ITC, NVL(SUM(g.CASH_TAX_PAID), 0) TOTAL_CASH,"
			+ "  NVL(SUM(CASE WHEN UPPER(g.GROWTH_TREND) = 'STRONG_GROWTH' THEN 1 ELSE 0 END), 0) STRONG_GROWTH_COUNT, "
			+ "  NVL(SUM(CASE WHEN UPPER(g.GROWTH_TREND) = 'GROWTH' THEN 1 ELSE 0 END), 0) GROWTH_COUNT, "
			+ "  NVL(SUM(CASE WHEN UPPER(g.GROWTH_TREND) = 'STABLE' THEN 1 ELSE 0 END), 0) STABLE_COUNT, "
			+ "  NVL(SUM(CASE WHEN UPPER(g.GROWTH_TREND) = 'DECLINING' THEN 1 ELSE 0 END), 0) DECLINE_COUNT, "
			+ "  NVL(SUM(CASE WHEN UPPER(g.GROWTH_TREND) = 'STRONG_DECLINE'  THEN 1 ELSE 0 END), 0) STRONG_DECLINE_COUNT, "
			+ "  NVL(SUM(CASE WHEN UPPER(r.RISK_LEVEL) = 'HIGH' THEN 1 ELSE 0 END), 0) HIGH_RISK_COUNT, "
			+ "  NVL(SUM(CASE WHEN UPPER(r.RISK_LEVEL) = 'MEDIUM' THEN 1 ELSE 0 END), 0) MEDIUM_RISK_COUNT, "
			+ "  NVL(SUM(CASE WHEN UPPER(r.RISK_LEVEL) = 'LOW' THEN 1 ELSE 0 END), 0) LOW_RISK_COUNT ";

	// ======================================================================
	// PERIODS
	// ======================================================================

	/** Cache this in the service layer: it is static between monthly loads. */
	public List<ReturnPeriodOptionDto> findReturnPeriods() {
		long start = System.currentTimeMillis();
		try {
			List<ReturnPeriodOptionDto> result = queryJdbc.query(RET_PRD_QUERY, (rs, rowNum) -> {
				String period = rs.getString("RET_PERIOD");
				return new ReturnPeriodOptionDto(formatPeriod(period), period);
			});
			log.debug("Loaded GST growth periods count={} durationMs={}", result.size(), elapsed(start));
			return result;
		} catch (DataAccessException ex) {
			throw translate("Failed to load return periods", "findReturnPeriods", ex);
		}
	}

	// ======================================================================
	// OFFICES
	// ======================================================================

	/**
	 * Period is not used by the query (office list is static) - cache in the
	 * service layer.
	 */
	public List<OfficeOptionResponse> findOffices(String period) {
		long start = System.currentTimeMillis();
		try {
			List<OfficeOptionResponse> result = queryJdbc.query(JURI_OFFICE_QUERY, this::mapOffice);
			log.debug("Loaded growth offices count={} durationMs={}", result.size(), elapsed(start));
			return result;
		} catch (DataAccessException ex) {
			throw translate("Failed to load offices", "findOffices period=" + safeLog(period), ex);
		}
	}

	public List<OfficeOptionResponse> findChargeCdOffices(String officeId) {
		if (!hasText(officeId)) {
			return List.of();
		}
		long start = System.currentTimeMillis();
		try {
			List<OfficeOptionResponse> result = queryJdbc.query(JURI_QUERY, this::mapOffice, officeId.trim());
			log.debug("Loaded charge offices chargeCd={} count={} durationMs={}", safeLog(officeId), result.size(),
					elapsed(start));
			return result;
		} catch (DataAccessException ex) {
			throw translate("Failed to load offices", "findChargeCdOffices chargeCd=" + safeLog(officeId), ex);
		}
	}

	private OfficeOptionResponse mapOffice(ResultSet rs, int rowNum) throws SQLException {
		return OfficeOptionResponse.builder().value(rs.getString("JURISDICTION_CODE"))
				.label(rs.getString("MERGED_JURISDICTION")).build();
	}

	// ======================================================================
	// DETAIL PAGE
	// ======================================================================

	public PageResponse<GrowthRowResponse> findGrowthRows(String period, String office, String search, String trend,
			String riskLevel, int page, int size) {

		validatePeriod(period);

		int safePage = Math.max(page, 0);
		int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
		long offset = (long) safePage * safeSize;

		long start = System.currentTimeMillis();
		FilterSql filter = buildFilter(period, office, search, trend, riskLevel);

		// Data query joins everything it displays / sorts on.
		String dataSql = "SELECT * FROM ( SELECT inner_query.*, ROWNUM RNUM FROM ( " + PAGE_COLUMNS + FROM_G + JOIN_D
				+ JOIN_J + JOIN_R + filter.where() + ORDER_BY + " ) inner_query WHERE ROWNUM <= ? ) WHERE RNUM > ? ";

		try {
			// Bind order: filter params, upper ROWNUM bound, lower RNUM bound.
			List<Object> parameters = new ArrayList<>(filter.parameters());
			parameters.add(offset + safeSize);
			parameters.add(offset);

			List<GrowthRowResponse> content = queryJdbc.query(dataSql, this::mapGrowthRow, parameters.toArray());

			/*
			 * Skip the COUNT query when the page itself proves the total: a short page is
			 * the last page, so total = offset + rows returned.
			 */
			long totalElements;
			if (content.size() < safeSize && (safePage == 0 || !content.isEmpty())) {
				totalElements = offset + content.size();
			} else {
				totalElements = count(filter);
			}

			int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / safeSize);

			log.info(
					"GST growth page loaded period={} office={} trend={} risk={} page={} size={} returned={} total={} durationMs={}",
					safeLog(period), safeLog(office), safeLog(trend), safeLog(riskLevel), safePage, safeSize,
					content.size(), totalElements, elapsed(start));

			return PageResponse.<GrowthRowResponse>builder().content(content).totalElements(totalElements)
					.totalPages(totalPages).page(safePage).size(safeSize).first(safePage == 0)
					.last(totalPages == 0 || safePage >= totalPages - 1).build();

		} catch (DataAccessException ex) {
			throw translate("Failed to load growth records", "findGrowthRows period=" + safeLog(period) + " office="
					+ safeLog(office) + " page=" + safePage + " size=" + safeSize, ex);
		}
	}

	/** COUNT only joins the tables the filter actually touches. */
	private long count(FilterSql filter) {
		String sql = "SELECT COUNT(*) " + FROM_G + (filter.needsDealer() ? JOIN_D : "")
				+ (filter.needsRisk() ? JOIN_R : "") + filter.where();
		Long total = queryJdbc.queryForObject(sql, Long.class, filter.parameters().toArray());
		return total == null ? 0L : total;
	}

	// ======================================================================
	// SUMMARY
	// ======================================================================

	public GrowthSummaryResponse findSummary(String period, String office) {

		validatePeriod(period);

		boolean byOffice = hasText(office);

		String sql = SUMMARY_COLUMNS + FROM_G + (byOffice ? JOIN_D : "") + JOIN_R + " WHERE g.RET_PERIOD = ? "
				+ (byOffice ? " AND d.ST_JURI = ? " : "");

		List<Object> params = new ArrayList<>();
		params.add(period);
		if (byOffice) {
			params.add(office.trim());
		}

		long start = System.currentTimeMillis();
		try {
			GrowthSummaryResponse response = queryJdbc.queryForObject(sql, (rs, rowNum) -> GrowthSummaryResponse
					.builder().totalGstins(rs.getLong("TOTAL_GSTINS")).totalTaxableValue(decimal(rs, "TOTAL_TAXABLE"))
					.totalOutputTax(decimal(rs, "TOTAL_OUTPUT")).totalEligibleItc(decimal(rs, "TOTAL_ITC"))
					.totalCashTaxPaid(decimal(rs, "TOTAL_CASH"))
					.strongGrowthCount(rs.getLong("STRONG_GROWTH_COUNT")).growthCount(rs.getLong("GROWTH_COUNT"))
					.stableCount(rs.getLong("STABLE_COUNT")).declineCount(rs.getLong("DECLINE_COUNT"))
					.strongDeclineCount(rs.getLong("STRONG_DECLINE_COUNT")).highRiskCount(rs.getLong("HIGH_RISK_COUNT"))
					.mediumRiskCount(rs.getLong("MEDIUM_RISK_COUNT")).lowRiskCount(rs.getLong("LOW_RISK_COUNT"))
					.build(), params.toArray());

			log.debug("Growth summary loaded period={} office={} durationMs={}", safeLog(period), safeLog(office),
					elapsed(start));
			return response;

		} catch (DataAccessException ex) {
			throw translate("Failed to load growth summary",
					"findSummary period=" + safeLog(period) + " office=" + safeLog(office), ex);
		}
	}

	// ======================================================================
	// SIX MONTH TREND
	// ======================================================================

	public List<GrowthTrendResponse> findTrend(String period, String office) {

		validatePeriod(period);

		boolean byOffice = hasText(office);

		/*
		 * Range on the plain PERIOD_DATE column (index friendly) instead of
		 * TO_DATE('01'||RET_PERIOD) on every row, which forces a full scan. [first day
		 * of (period - 5 months), first day of (period + 1 month))
		 */
		LocalDate periodStart = LocalDate.parse("01" + period, PERIOD_FMT);
		Date from = Date.valueOf(periodStart.minusMonths(5));
		Date toExclusive = Date.valueOf(periodStart.plusMonths(1));

		String sql = """
				SELECT g.RET_PERIOD,
				       MIN(g.PERIOD_DATE) PERIOD_DATE,
				       NVL(SUM(g.TAXABLE_VALUE), 0) TAXABLE_VALUE,
				       NVL(SUM(g.OUTPUT_TAX), 0) OUTPUT_TAX,
				       NVL(SUM(g.ELIGIBLE_ITC), 0) ELIGIBLE_ITC,
				       NVL(SUM(g.CASH_TAX_PAID), 0) CASH_TAX_PAID
				""" + FROM_G + (byOffice ? JOIN_D : "") + """
				WHERE g.PERIOD_DATE >= ? AND g.PERIOD_DATE < ?
				""" + (byOffice ? " AND d.ST_JURI = ? " : "") + " GROUP BY g.RET_PERIOD ORDER BY MIN(g.PERIOD_DATE) ";

		List<Object> params = new ArrayList<>();
		params.add(from);
		params.add(toExclusive);
		if (byOffice) {
			params.add(office.trim());
		}

		long start = System.currentTimeMillis();
		try {
			List<GrowthTrendResponse> result = queryJdbc.query(sql, (rs, rowNum) -> {
				String retPeriod = rs.getString("RET_PERIOD");
				return GrowthTrendResponse.builder().retPeriod(retPeriod).label(formatPeriod(retPeriod))
						.taxableValue(decimal(rs, "TAXABLE_VALUE")).outputTax(decimal(rs, "OUTPUT_TAX"))
						.eligibleItc(decimal(rs, "ELIGIBLE_ITC")).cashTaxPaid(decimal(rs, "CASH_TAX_PAID")).build();
			}, params.toArray());

			log.debug("Growth trend loaded period={} office={} points={} durationMs={}", safeLog(period),
					safeLog(office), result.size(), elapsed(start));
			return result;

		} catch (DataAccessException ex) {
			throw translate("Failed to load growth trend",
					"findTrend period=" + safeLog(period) + " office=" + safeLog(office), ex);
		}
	}

	// ======================================================================
	// STREAMING CSV EXPORTS
	// ======================================================================

	private static final String[] FULL_HEADER = { "GSTIN", "Trade Name", "ST Juri", "Office Name", "Return Period",
			"Period Date", "Taxable Value", "Output Tax", "Eligible ITC", "Utilized ITC", "Cash Tax Paid",
			"RCM Total Tax", "MoM Taxable Growth %", "MoM Output Tax Growth %", "MoM ITC Growth %", "MoM Cash Growth %",
			"YoY Taxable Growth %", "YoY Output Tax Growth %", "YoY ITC Growth %", "YoY Cash Growth %",
			"Filing Delay Days", "Growth Trend", "Risk Score", "Risk Level" };

	private static final String[] NO_RISK_HEADER = { "GSTIN", "Trade Name", "ST Juri", "Office Name", "Return Period",
			"Period Date", "Taxable Value", "Output Tax", "Eligible ITC", "Utilized ITC", "Cash Tax Paid",
			"RCM Total Tax", "MoM Taxable Growth %", "MoM Output Tax Growth %", "MoM ITC Growth %",
			"YoY Taxable Growth %", "YoY Output Tax Growth %", "YoY ITC Growth %", "Avg 3M Taxable", "Avg 6M Taxable",
			"Avg 12M Taxable", "Filing Delay Days", "Growth Trend" };

	@FunctionalInterface
	private interface CsvRowMapper {
		String[] cells(ResultSet rs) throws SQLException;
	}

	/** Full export including risk score / level. */
	public void exportCsv(String period, String office, String search, String trend, String riskLevel, Writer writer) {

		validatePeriod(period);
		FilterSql filter = buildFilter(period, office, search, trend, riskLevel);

		String sql = """
				SELECT g.GSTIN, d.TRADE_NAME, d.ST_JURI, j.JURISDICTION_NAME,
				       g.RET_PERIOD, g.PERIOD_DATE,
				       g.TAXABLE_VALUE, g.OUTPUT_TAX, g.ELIGIBLE_ITC, g.UTILIZED_ITC, g.CASH_TAX_PAID, g.RCM_TOTAL_TAX,
				       g.MOM_TAXABLE_GROWTH, g.MOM_OUTPUT_TAX_GROWTH, g.MOM_ITC_GROWTH, g.MOM_CASH_GROWTH,
				       g.YOY_TAXABLE_GROWTH, g.YOY_OUTPUT_TAX_GROWTH, g.YOY_ITC_GROWTH, g.YOY_CASH_GROWTH,
				       g.FILING_DELAY_DAYS, g.GROWTH_TREND, r.RISK_SCORE, r.RISK_LEVEL
				""" + FROM_G + JOIN_D + JOIN_J + JOIN_R + filter.where() + ORDER_BY;

		streamCsv("full", sql, filter, FULL_HEADER, rs -> new String[] {

				text(rs.getString("GSTIN")), text(rs.getString("TRADE_NAME")), text(rs.getString("ST_JURI")),
				text(rs.getString("JURISDICTION_NAME")), text(rs.getString("RET_PERIOD")), csvDate(rs, "PERIOD_DATE"),

				num(rs, "TAXABLE_VALUE"), num(rs, "OUTPUT_TAX"), num(rs, "ELIGIBLE_ITC"), num(rs, "UTILIZED_ITC"),
				num(rs, "CASH_TAX_PAID"), num(rs, "RCM_TOTAL_TAX"),

				num(rs, "MOM_TAXABLE_GROWTH"), num(rs, "MOM_OUTPUT_TAX_GROWTH"), num(rs, "MOM_ITC_GROWTH"),
				num(rs, "MOM_CASH_GROWTH"),

				num(rs, "YOY_TAXABLE_GROWTH"), num(rs, "YOY_OUTPUT_TAX_GROWTH"), num(rs, "YOY_ITC_GROWTH"),
				num(rs, "YOY_CASH_GROWTH"),

				csvInt(rs, "FILING_DELAY_DAYS"), text(rs.getString("GROWTH_TREND")), num(rs, "RISK_SCORE"),
				text(rs.getString("RISK_LEVEL")) }, writer, period, office);
	}

	/**
	 * Export without risk columns; risk table is joined only if a risk filter is
	 * applied.
	 */
	public void exportGrowthCsv(String period, String office, String search, String trend, String riskLevel,
			Writer writer) {

		validatePeriod(period);
		FilterSql filter = buildFilter(period, office, search, trend, riskLevel);

		String sql = """
				SELECT g.GSTIN, d.TRADE_NAME, d.ST_JURI, j.JURISDICTION_NAME,
				       g.RET_PERIOD, g.PERIOD_DATE,
				       g.TAXABLE_VALUE, g.OUTPUT_TAX, g.ELIGIBLE_ITC, g.UTILIZED_ITC, g.CASH_TAX_PAID, g.RCM_TOTAL_TAX,
				       g.MOM_TAXABLE_GROWTH, g.MOM_OUTPUT_TAX_GROWTH, g.MOM_ITC_GROWTH,
				       g.YOY_TAXABLE_GROWTH, g.YOY_OUTPUT_TAX_GROWTH, g.YOY_ITC_GROWTH,
				       g.AVG_3M_TAXABLE, g.AVG_6M_TAXABLE, g.AVG_12M_TAXABLE,
				       g.FILING_DELAY_DAYS, g.GROWTH_TREND
				""" + FROM_G + JOIN_D + JOIN_J + (filter.needsRisk() ? JOIN_R : "") + filter.where() + ORDER_BY;

		streamCsv("no-risk", sql, filter, NO_RISK_HEADER, rs -> new String[] {

				text(rs.getString("GSTIN")), text(rs.getString("TRADE_NAME")), text(rs.getString("ST_JURI")),
				text(rs.getString("JURISDICTION_NAME")), text(rs.getString("RET_PERIOD")), csvDate(rs, "PERIOD_DATE"),

				num(rs, "TAXABLE_VALUE"), num(rs, "OUTPUT_TAX"), num(rs, "ELIGIBLE_ITC"), num(rs, "UTILIZED_ITC"),
				num(rs, "CASH_TAX_PAID"), num(rs, "RCM_TOTAL_TAX"),

				num(rs, "MOM_TAXABLE_GROWTH"), num(rs, "MOM_OUTPUT_TAX_GROWTH"), num(rs, "MOM_ITC_GROWTH"),
				num(rs, "YOY_TAXABLE_GROWTH"), num(rs, "YOY_OUTPUT_TAX_GROWTH"), num(rs, "YOY_ITC_GROWTH"),

				num(rs, "AVG_3M_TAXABLE"), num(rs, "AVG_6M_TAXABLE"), num(rs, "AVG_12M_TAXABLE"),

				csvInt(rs, "FILING_DELAY_DAYS"), text(rs.getString("GROWTH_TREND")) }, writer, period, office);
	}

	private void streamCsv(String variant, String sql, FilterSql filter, String[] header, CsvRowMapper mapper,
			Writer writer, String period, String office) {

		long start = System.currentTimeMillis();
		final long[] rows = { 0L };

		BufferedWriter out = writer instanceof BufferedWriter bw ? bw : new BufferedWriter(writer);

		try {
			out.write('\uFEFF'); // UTF-8 BOM so Excel detects the encoding

			String[] headerCells = new String[header.length];
			for (int i = 0; i < header.length; i++) {
				headerCells[i] = text(header[i]);
			}
			writeLine(out, headerCells);

			exportJdbc.query(sql, (RowCallbackHandler) rs -> {
				try {
					writeLine(out, mapper.cells(rs));
					if (++rows[0] % EXPORT_FETCH_SIZE == 0) {
						out.flush();
					}
				} catch (IOException ex) {
					throw new CsvWriteException(ex);
				}
			}, filter.parameters().toArray());

			out.flush();

			log.info("GST growth CSV completed variant={} period={} office={} rows={} durationMs={}", variant,
					safeLog(period), safeLog(office), rows[0], elapsed(start));

		} catch (CsvWriteException ex) {
			handleCsvIo(variant, period, office, rows[0], start, ex.getCause());

		} catch (IOException ex) {
			handleCsvIo(variant, period, office, rows[0], start, ex);

		} catch (DataAccessException ex) {
			throw translate("Failed to export growth data", "exportCsv variant=" + variant + " period="
					+ safeLog(period) + " office=" + safeLog(office) + " rows=" + rows[0], ex);
		}
	}

	/**
	 * A browser that closes the download mid-stream is normal, not a server fault:
	 * log at WARN and stop quietly (the response is already committed, so throwing
	 * would only trigger a second, useless error write).
	 */
	private void handleCsvIo(String variant, String period, String office, long rows, long start, Throwable cause) {
		if (isClientAbort(cause)) {
			log.warn("CSV download aborted by client variant={} period={} office={} rows={} durationMs={}", variant,
					safeLog(period), safeLog(office), rows, elapsed(start));
			return;
		}
		log.error("CSV output stream failed variant={} period={} office={} rows={} durationMs={}", variant,
				safeLog(period), safeLog(office), rows, elapsed(start), cause);
		throw new GrowthDataAccessException("Failed to write CSV export", cause);
	}

	private boolean isClientAbort(Throwable ex) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			String name = t.getClass().getSimpleName();
			String msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
			if (name.contains("ClientAbort") || msg.contains("broken pipe") || msg.contains("connection reset")
					|| msg.contains("connection was aborted")) {
				return true;
			}
		}
		return false;
	}

	// ======================================================================
	// FILTER BUILDER
	// ======================================================================

	private FilterSql buildFilter(String period, String office, String search, String trend, String riskLevel) {

		StringBuilder where = new StringBuilder(" WHERE g.RET_PERIOD = ? ");
		List<Object> params = new ArrayList<>();
		params.add(period);

		boolean needsDealer = false;
		boolean needsRisk = false;

		if (hasText(office)) {
			where.append(" AND d.ST_JURI = ? ");
			params.add(office.trim());
			needsDealer = true;
		}

		if (hasText(search)) {
			String term = search.trim().toUpperCase(Locale.ROOT);

			if (GSTIN_PATTERN.matcher(term).matches()) {
				// Full GSTIN: exact match, no dealer join, no wildcard scan.
				where.append(" AND g.GSTIN = ? ");
				params.add(term);
			} else {
				String escaped = escapeLike(term);
				where.append(" AND ( g.GSTIN LIKE ? ESCAPE '\\' OR UPPER(d.TRADE_NAME) LIKE ? ESCAPE '\\' ) ");
				params.add(escaped + "%"); // GSTIN is stored upper case: prefix match
				params.add("%" + escaped + "%");
				needsDealer = true;
			}
		}

		if (hasText(trend)) {
			where.append(" AND UPPER(g.GROWTH_TREND) = ? ");
			params.add(trend.trim().toUpperCase(Locale.ROOT));
		}

		if (hasText(riskLevel)) {
			where.append(" AND UPPER(r.RISK_LEVEL) = ? ");
			params.add(riskLevel.trim().toUpperCase(Locale.ROOT));
			needsRisk = true;
		}

		return new FilterSql(where.toString(), List.copyOf(params), needsDealer, needsRisk);
	}

	private String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	// ======================================================================
	// MAPPER
	// ======================================================================

	private GrowthRowResponse mapGrowthRow(ResultSet rs, int rowNum) throws SQLException {

		Date date = rs.getDate("PERIOD_DATE");

		return GrowthRowResponse.builder()

				.gstin(rs.getString("GSTIN")).tradeName(rs.getString("TRADE_NAME")).stJuri(rs.getString("ST_JURI"))
				.officeName(rs.getString("JURISDICTION_NAME")).retPeriod(rs.getString("RET_PERIOD"))
				.periodDate(date == null ? null : date.toLocalDate()).taxableValue(decimal(rs, "TAXABLE_VALUE"))
				.outputTax(decimal(rs, "OUTPUT_TAX")).eligibleItc(decimal(rs, "ELIGIBLE_ITC"))
				.utilizedItc(decimal(rs, "UTILIZED_ITC")).cashTaxPaid(decimal(rs, "CASH_TAX_PAID"))
//				.rcmTotalTax(decimal(rs, "RCM_TOTAL_TAX"))
				.momTaxableGrowth(nullableDecimal(rs, "MOM_TAXABLE_GROWTH"))
				.momOutputTaxGrowth(nullableDecimal(rs, "MOM_OUTPUT_TAX_GROWTH"))
//				.momItcGrowth(nullableDecimal(rs, "MOM_ITC_GROWTH"))
//				.momCashGrowth(nullableDecimal(rs, "MOM_CASH_GROWTH"))
//				.yoyTaxableGrowth(nullableDecimal(rs, "YOY_TAXABLE_GROWTH"))
				.yoyOutputTaxGrowth(nullableDecimal(rs, "YOY_OUTPUT_TAX_GROWTH"))
//				.yoyItcGrowth(nullableDecimal(rs, "YOY_ITC_GROWTH"))
//				.yoyCashGrowth(nullableDecimal(rs, "YOY_CASH_GROWTH"))
//				.avg3mTaxable(nullableDecimal(rs, "AVG_3M_TAXABLE"))
//				.avg6mTaxable(nullableDecimal(rs, "AVG_6M_TAXABLE"))
//				.avg12mTaxable(nullableDecimal(rs, "AVG_12M_TAXABLE"))
//				.avg3mOutputTax(nullableDecimal(rs, "AVG_3M_OUTPUT_TAX"))
//				.avg6mOutputTax(nullableDecimal(rs, "AVG_6M_OUTPUT_TAX"))
//				.avg12mOutputTax(nullableDecimal(rs, "AVG_12M_OUTPUT_TAX"))
//				.avg3mItc(nullableDecimal(rs, "AVG_3M_ITC")).avg6mItc(nullableDecimal(rs, "AVG_6M_ITC"))
//				.avg12mItc(nullableDecimal(rs, "AVG_12M_ITC"))
				.growthTrend(rs.getString("GROWTH_TREND")).riskScore(nullableDecimal(rs, "RISK_SCORE"))
				.riskLevel(rs.getString("RISK_LEVEL")).build();
	}

	// ======================================================================
	// CSV HELPERS
	// ======================================================================

	private void writeLine(Writer writer, String[] cells) throws IOException {
		for (int i = 0; i < cells.length; i++) {
			if (i > 0) {
				writer.write(',');
			}
			writer.write(cells[i]);
		}
		writer.write("\r\n");
	}

	/**
	 * Text cell: guards against CSV/Excel formula injection and quotes when needed.
	 * Numbers must NOT go through here - a negative growth value such as -5.2 would
	 * be corrupted by the formula guard.
	 */
	private String text(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}

		String safe = value;
		char first = safe.charAt(0);
		if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
			safe = "'" + safe;
		}

		boolean quote = safe.indexOf(',') >= 0 || safe.indexOf('"') >= 0 || safe.indexOf('\n') >= 0
				|| safe.indexOf('\r') >= 0;

		safe = safe.replace("\"", "\"\"");
		return quote ? "\"" + safe + "\"" : safe;
	}

	private String num(ResultSet rs, String column) throws SQLException {
		BigDecimal value = rs.getBigDecimal(column);
		return value == null ? "" : value.toPlainString();
	}

	private String csvInt(ResultSet rs, String column) throws SQLException {
		BigDecimal value = rs.getBigDecimal(column);
		return value == null ? "" : Integer.toString(value.intValue());
	}

	private String csvDate(ResultSet rs, String column) throws SQLException {
		Date value = rs.getDate(column);
		return value == null ? "" : value.toLocalDate().toString();
	}

	// ======================================================================
	// EXCEPTION / VALIDATION / UTILITIES
	// ======================================================================

	/**
	 * Single place where Spring data-access failures are classified, logged and
	 * wrapped, so every method reports errors the same way.
	 */
	private GrowthDataAccessException translate(String userMessage, String context, DataAccessException ex) {
		if (ex instanceof QueryTimeoutException) {
			log.error("Query timed out ({}s) context=[{}]", QUERY_TIMEOUT_SECONDS, context, ex);
		} else if (ex instanceof DataAccessResourceFailureException) {
			log.error("Database unavailable / connection failure context=[{}]", context, ex);
		} else {
			log.error("Data access failure context=[{}]", context, ex);
		}
		return new GrowthDataAccessException(userMessage, ex);
	}

	/** Fail fast with a clear message instead of an Oracle ORA-01843 later. */
	private void validatePeriod(String period) {
		if (period == null || !PERIOD_PATTERN.matcher(period).matches()) {
			throw new IllegalArgumentException("Invalid return period (expected MMYYYY): " + safeLog(period));
		}
	}

	private BigDecimal decimal(ResultSet rs, String column) throws SQLException {
		BigDecimal value = rs.getBigDecimal(column);
		return value == null ? BigDecimal.ZERO : value;
	}

	private BigDecimal nullableDecimal(ResultSet rs, String column) throws SQLException {
		return rs.getBigDecimal(column);
	}

	private boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}

	private long elapsed(long start) {
		return System.currentTimeMillis() - start;
	}

	private String safeLog(String value) {
		if (!hasText(value)) {
			return "-";
		}
		return value.replaceAll("[\\r\\n\\t]", "_");
	}

	private String formatPeriod(String period) {
		if (period == null || !PERIOD_PATTERN.matcher(period).matches()) {
			return period;
		}
		int month = Integer.parseInt(period.substring(0, 2));
		return MONTHS[month - 1] + " " + period.substring(2);
	}

	private record FilterSql(String where, List<Object> parameters, boolean needsDealer, boolean needsRisk) {
	}

	private static final class CsvWriteException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private CsvWriteException(Throwable cause) {
			super(cause);
		}
	}
}