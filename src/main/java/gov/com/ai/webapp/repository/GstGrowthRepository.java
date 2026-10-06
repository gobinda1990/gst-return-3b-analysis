package gov.com.ai.webapp.repository;

import gov.com.ai.webapp.exception.GrowthDataAccessException;
import gov.com.ai.webapp.model.ReturnPeriodOptionDto;
import gov.com.ai.webapp.model.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Repository
@RequiredArgsConstructor
public class GstGrowthRepository {

	private static final int EXPORT_FETCH_SIZE = 1000;

	private final JdbcTemplate jdbcTemplate;
	
	private static final String RET_PRD_QUERY=" SELECT DISTINCT RET_PERIOD, PERIOD_DATE FROM GST_3B_GROWTH_ANALYTICS  "
			+ " ORDER BY PERIOD_DATE DESC ";
	
	private static final String JURI_OFFICE_QUERY=" SELECT JURISDICTION_CODE, MERGED_JURISDICTION FROM gst_master_juri_new  "
			+ " ORDER BY JURISDICTION_CODE ";
	
	

	// ======== COMMON JOINS Risk profile MUST join on GSTIN + RET_PERIOD.
	
	private static final String COMMON_FROM = " FROM GST_3B_GROWTH_ANALYTICS g 	LEFT JOIN  "
			+ " GST_DEALER_MASTER_WBCOMTAX d ON d.GSTIN = g.GSTIN "
			+ " LEFT JOIN gst_master_jurisdiction j  ON j.JURISDICTION_CODE = d.ST_JURI "
			+ " LEFT JOIN GST_RET_3B_RISK_PROFILE r   ON r.GSTIN = g.GSTIN "
			+ " AND r.RET_PERIOD = g.RET_PERIOD " ;
			

	//============== PERIODS

	public List<ReturnPeriodOptionDto> findReturnPeriods() {
		long start = System.currentTimeMillis();
		try {

			List<ReturnPeriodOptionDto> result = jdbcTemplate.query(RET_PRD_QUERY, (rs, rowNum) -> {

				String period = rs.getString("RET_PERIOD");

				return new ReturnPeriodOptionDto(formatPeriod(period), period);
			});

			log.debug("Loaded GST growth periods count={} durationMs={}", result.size(), elapsed(start));

			return result;

		} catch (DataAccessException ex) {

			log.error("Failed loading GST growth return periods", ex);

			throw new GrowthDataAccessException("Failed to load return periods", ex);
		}
	}

	//========= OFFICES ========================

	public List<OfficeOptionResponse> findOffices(String period) {

		long start = System.currentTimeMillis();
		try {
		    List<OfficeOptionResponse> result = jdbcTemplate.query(
		        JURI_OFFICE_QUERY,
		        (rs, rowNum) -> OfficeOptionResponse.builder()
		                .value(rs.getString("JURISDICTION_CODE"))
		                .label(rs.getString("MERGED_JURISDICTION"))
		                .build()); 

		    log.debug("Loaded growth offices period={} count={} durationMs={}", period, result.size(), elapsed(start));
		    return result;

		} catch (DataAccessException ex) {
		    log.error("Failed loading growth offices period={}", period, ex);
		    throw new GrowthDataAccessException("Failed to load offices", ex);
		}
	}

	/*
	 * ======================================================== DETAIL PAGE
	 * ========================================================
	 */

	public PageResponse<GrowthRowResponse> findGrowthRows(String period, String office, String search, String trend,
			String riskLevel, int page, int size) {

		long start = System.currentTimeMillis();

		FilterSql filter = buildFilter(period, office, search, trend, riskLevel);

		String countSql = "SELECT COUNT(*) " + COMMON_FROM + filter.where();
		
		String dataSql = """
				SELECT * FROM (
				    SELECT inner_query.*, ROWNUM RNUM FROM (

				        SELECT

				            g.GSTIN,

				            d.TRADE_NAME,
				            d.ST_JURI,
				            j.JURISDICTION_NAME,

				            g.RET_PERIOD,
				            g.PERIOD_DATE,

				            g.TAXABLE_VALUE,
				            g.OUTPUT_TAX,

				            g.ELIGIBLE_ITC,
				            g.UTILIZED_ITC,

				            g.CASH_TAX_PAID,
				            g.RCM_TOTAL_TAX,

				            g.MOM_TAXABLE_GROWTH,
				            g.MOM_OUTPUT_TAX_GROWTH,
				            g.MOM_ITC_GROWTH,
				            g.MOM_CASH_GROWTH,

				            g.YOY_TAXABLE_GROWTH,
				            g.YOY_OUTPUT_TAX_GROWTH,
				            g.YOY_ITC_GROWTH,
				            g.YOY_CASH_GROWTH,

				            g.AVG_3M_TAXABLE,
				            g.AVG_6M_TAXABLE,
				            g.AVG_12M_TAXABLE,

				            g.AVG_3M_OUTPUT_TAX,
				            g.AVG_6M_OUTPUT_TAX,
				            g.AVG_12M_OUTPUT_TAX,

				            g.AVG_3M_ITC,
				            g.AVG_6M_ITC,
				            g.AVG_12M_ITC,

				            g.FILING_DELAY_DAYS,
				            g.GROWTH_TREND,

				            r.RISK_SCORE,
				            r.RISK_LEVEL

				        """ + COMMON_FROM + filter.where() + """

				        ORDER BY
				            j.JURISDICTION_NAME NULLS LAST,
				            d.TRADE_NAME NULLS LAST,
				            g.GSTIN

				    ) inner_query
				    WHERE ROWNUM <= ?
				)
				WHERE RNUM > ?

				""";

		try {

			Long total = jdbcTemplate.queryForObject(countSql, Long.class, filter.parameters().toArray());

			long totalElements = total == null ? 0 : total;

			long offset = (long) page * size;

			List<Object> parameters = new ArrayList<>(filter.parameters());

			/*
			 * Bind order must match placeholder order in the SQL text above: filter params
			 * first (used inside the innermost WHERE), then the upper ROWNUM bound, then
			 * the lower RNUM bound.
			 */
			parameters.add(offset + size);
			parameters.add(offset);

			List<GrowthRowResponse> content = jdbcTemplate.query(dataSql, this::mapGrowthRow, parameters.toArray());

			int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / size);

			log.info(
					"GST growth page loaded period={} office={} trend={} risk={} page={} size={} returned={} total={} durationMs={}",
					period, safeLog(office), safeLog(trend), safeLog(riskLevel), page, size, content.size(),
					totalElements, elapsed(start));

			return PageResponse.<GrowthRowResponse>builder().content(content).totalElements(totalElements)
					.totalPages(totalPages).page(page).size(size).first(page == 0)
					.last(totalPages == 0 || page >= totalPages - 1).build();

		} catch (DataAccessException ex) {

			log.error("Growth page query failed period={} office={} page={} size={}", period, safeLog(office), page,
					size, ex);

			throw new GrowthDataAccessException("Failed to load growth records", ex);
		}
	}

	//================ SUMMARY

	public GrowthSummaryResponse findSummary(String period, String office) {

		StringBuilder sql = new StringBuilder("""

				SELECT

				    COUNT(*) TOTAL_GSTINS,

				    NVL(
				        SUM(g.TAXABLE_VALUE),
				        0
				    ) TOTAL_TAXABLE,

				    NVL(
				        SUM(g.OUTPUT_TAX),
				        0
				    ) TOTAL_OUTPUT,

				    NVL(
				        SUM(g.ELIGIBLE_ITC),
				        0
				    ) TOTAL_ITC,

				    NVL(
				        SUM(g.CASH_TAX_PAID),
				        0
				    ) TOTAL_CASH,

				    NVL(
				        AVG(g.MOM_TAXABLE_GROWTH),
				        0
				    ) AVG_MOM_TAXABLE,

				    NVL(
				        AVG(g.MOM_OUTPUT_TAX_GROWTH),
				        0
				    ) AVG_MOM_OUTPUT,

				    NVL(
				        AVG(g.YOY_TAXABLE_GROWTH),
				        0
				    ) AVG_YOY_TAXABLE,

				    NVL(
				        AVG(g.YOY_OUTPUT_TAX_GROWTH),
				        0
				    ) AVG_YOY_OUTPUT,

				    SUM(
				        CASE
				            WHEN UPPER(g.GROWTH_TREND)
				                 = 'STRONG_GROWTH'
				            THEN 1
				            ELSE 0
				        END
				    ) STRONG_GROWTH_COUNT,

				    SUM(
				        CASE
				            WHEN UPPER(g.GROWTH_TREND)
				                 = 'GROWTH'
				            THEN 1
				            ELSE 0
				        END
				    ) GROWTH_COUNT,

				    SUM(
				        CASE
				            WHEN UPPER(g.GROWTH_TREND)
				                 = 'STABLE'
				            THEN 1
				            ELSE 0
				        END
				    ) STABLE_COUNT,

				    SUM(
				        CASE
				            WHEN UPPER(g.GROWTH_TREND)
				                 IN (
				                    'DECLINE',
				                    'DECLINING'
				                 )
				            THEN 1
				            ELSE 0
				        END
				    ) DECLINE_COUNT,

				    SUM(
				        CASE
				            WHEN UPPER(g.GROWTH_TREND)
				                 IN (
				                    'STRONG_DECLINE',
				                    'SHARP_DECLINE'
				                 )
				            THEN 1
				            ELSE 0
				        END
				    ) STRONG_DECLINE_COUNT,

				    SUM(
				        CASE
				            WHEN UPPER(r.RISK_LEVEL)
				                 = 'HIGH'
				            THEN 1
				            ELSE 0
				        END
				    ) HIGH_RISK_COUNT,

				    SUM(
				        CASE
				            WHEN UPPER(r.RISK_LEVEL)
				                 = 'MEDIUM'
				            THEN 1
				            ELSE 0
				        END
				    ) MEDIUM_RISK_COUNT,

				    SUM(
				        CASE
				            WHEN UPPER(r.RISK_LEVEL)
				                 = 'LOW'
				            THEN 1
				            ELSE 0
				        END
				    ) LOW_RISK_COUNT

				""" + COMMON_FROM + """

				WHERE g.RET_PERIOD = ?

				""");

		List<Object> params = new ArrayList<>();

		params.add(period);

		if (hasText(office)) {

			sql.append(" AND d.ST_JURI = ? ");

			params.add(office);
		}

		long start = System.currentTimeMillis();

		try {

			GrowthSummaryResponse response = jdbcTemplate.queryForObject(sql.toString(),

					(rs, rowNum) -> GrowthSummaryResponse.builder()

							.totalGstins(rs.getLong("TOTAL_GSTINS"))

							.totalTaxableValue(decimal(rs, "TOTAL_TAXABLE"))

							.totalOutputTax(decimal(rs, "TOTAL_OUTPUT"))

							.totalEligibleItc(decimal(rs, "TOTAL_ITC"))

							.totalCashTaxPaid(decimal(rs, "TOTAL_CASH"))

							.avgMomTaxableGrowth(decimal(rs, "AVG_MOM_TAXABLE"))

							.avgMomOutputTaxGrowth(decimal(rs, "AVG_MOM_OUTPUT"))

							.avgYoyTaxableGrowth(decimal(rs, "AVG_YOY_TAXABLE"))

							.avgYoyOutputTaxGrowth(decimal(rs, "AVG_YOY_OUTPUT"))

							.strongGrowthCount(rs.getLong("STRONG_GROWTH_COUNT"))

							.growthCount(rs.getLong("GROWTH_COUNT"))

							.stableCount(rs.getLong("STABLE_COUNT"))

							.declineCount(rs.getLong("DECLINE_COUNT"))

							.strongDeclineCount(rs.getLong("STRONG_DECLINE_COUNT"))

							.highRiskCount(rs.getLong("HIGH_RISK_COUNT"))

							.mediumRiskCount(rs.getLong("MEDIUM_RISK_COUNT"))

							.lowRiskCount(rs.getLong("LOW_RISK_COUNT"))

							.build(),

					params.toArray());

			log.debug("Growth summary loaded period={} office={} durationMs={}", period, safeLog(office),
					elapsed(start));

			return response;

		} catch (DataAccessException ex) {

			log.error("Growth summary query failed period={} office={}", period, safeLog(office), ex);

			throw new GrowthDataAccessException("Failed to load growth summary", ex);
		}
	}

	/*
	 * ======================================================== SIX MONTH TREND
	 * ========================================================
	 */

	public List<GrowthTrendResponse> findTrend(String period, String office) {

		StringBuilder sql = new StringBuilder("""

				SELECT

				    g.RET_PERIOD,

				    NVL(
				        SUM(g.TAXABLE_VALUE),
				        0
				    ) TAXABLE_VALUE,

				    NVL(
				        SUM(g.OUTPUT_TAX),
				        0
				    ) OUTPUT_TAX,

				    NVL(
				        SUM(g.ELIGIBLE_ITC),
				        0
				    ) ELIGIBLE_ITC,

				    NVL(
				        SUM(g.CASH_TAX_PAID),
				        0
				    ) CASH_TAX_PAID

				FROM GST_3B_GROWTH_ANALYTICS g

				LEFT JOIN GST_DEALER_MASTER_WBCOMTAX d
				       ON d.GSTIN = g.GSTIN

				WHERE TO_DATE(
				          '01' || g.RET_PERIOD,
				          'DDMMYYYY'
				      )
				      BETWEEN
				      ADD_MONTHS(
				          TO_DATE(
				              '01' || ?,
				              'DDMMYYYY'
				          ),
				          -5
				      )
				      AND
				      TO_DATE(
				          '01' || ?,
				          'DDMMYYYY'
				      )

				""");

		List<Object> params = new ArrayList<>();

		params.add(period);
		params.add(period);

		if (hasText(office)) {

			sql.append(" AND d.ST_JURI = ? ");

			params.add(office);
		}

		sql.append("""

				GROUP BY g.RET_PERIOD

				ORDER BY
				    TO_DATE(
				        '01' || g.RET_PERIOD,
				        'DDMMYYYY'
				    )

				""");

		long start = System.currentTimeMillis();

		try {

			List<GrowthTrendResponse> result = jdbcTemplate.query(sql.toString(),

					(rs, rowNum) -> {

						String retPeriod = rs.getString("RET_PERIOD");

						return GrowthTrendResponse.builder()

								.retPeriod(retPeriod)

								.label(formatPeriod(retPeriod))

								.taxableValue(decimal(rs, "TAXABLE_VALUE"))

								.outputTax(decimal(rs, "OUTPUT_TAX"))

								.eligibleItc(decimal(rs, "ELIGIBLE_ITC"))

								.cashTaxPaid(decimal(rs, "CASH_TAX_PAID"))

								.build();
					},

					params.toArray());

			log.debug("Growth trend loaded period={} office={} points={} durationMs={}", period, safeLog(office),
					result.size(), elapsed(start));

			return result;

		} catch (DataAccessException ex) {

			log.error("Growth trend query failed period={} office={}", period, safeLog(office), ex);

			throw new GrowthDataAccessException("Failed to load growth trend", ex);
		}
	}

	/*
	 * ======================================================== FULL STREAMING CSV
	 * ========================================================
	 */

	public void exportCsv(String period, String office, String search, String trend, String riskLevel, Writer writer) {

		FilterSql filter = buildFilter(period, office, search, trend, riskLevel);

		String sql = """

				SELECT

				    g.GSTIN,

				    d.TRADE_NAME,
				    d.ST_JURI,
				    j.JURISDICTION_NAME,

				    g.RET_PERIOD,
				    g.PERIOD_DATE,

				    g.TAXABLE_VALUE,
				    g.OUTPUT_TAX,
				    g.ELIGIBLE_ITC,
				    g.UTILIZED_ITC,
				    g.CASH_TAX_PAID,
				    g.RCM_TOTAL_TAX,

				    g.MOM_TAXABLE_GROWTH,
				    g.MOM_OUTPUT_TAX_GROWTH,
				    g.MOM_ITC_GROWTH,
				    g.MOM_CASH_GROWTH,

				    g.YOY_TAXABLE_GROWTH,
				    g.YOY_OUTPUT_TAX_GROWTH,
				    g.YOY_ITC_GROWTH,
				    g.YOY_CASH_GROWTH,

				    g.FILING_DELAY_DAYS,
				    g.GROWTH_TREND,

				    r.RISK_SCORE,
				    r.RISK_LEVEL

				""" + COMMON_FROM + filter.where() + """

				ORDER BY
				    j.JURISDICTION_NAME NULLS LAST,
				    d.TRADE_NAME NULLS LAST,
				    g.GSTIN

				""";

		long start = System.currentTimeMillis();

		final long[] rows = { 0L };

		try {

			BufferedWriter out = writer instanceof BufferedWriter ? (BufferedWriter) writer
					: new BufferedWriter(writer);

			/*
			 * UTF-8 BOM for Excel.
			 */
			out.write('\uFEFF');

			writeCsvRow(out,

					"GSTIN", "Trade Name", "ST Juri", "Office Name",

					"Return Period", "Period Date",

					"Taxable Value", "Output Tax", "Eligible ITC", "Utilized ITC", "Cash Tax Paid", "RCM Total Tax",

					"MoM Taxable Growth %", "MoM Output Tax Growth %", "MoM ITC Growth %", "MoM Cash Growth %",

					"YoY Taxable Growth %", "YoY Output Tax Growth %", "YoY ITC Growth %", "YoY Cash Growth %",

					"Filing Delay Days", "Growth Trend",

					"Risk Score", "Risk Level");

			jdbcTemplate.query(

					connection -> {

						var statement = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY,
								ResultSet.CONCUR_READ_ONLY);

						int index = 1;

						for (Object parameter : filter.parameters()) {

							statement.setObject(index++, parameter);
						}

						statement.setFetchSize(EXPORT_FETCH_SIZE);

						return statement;
					},

					(RowCallbackHandler) rs -> {

						try {

							Date periodDate = rs.getDate("PERIOD_DATE");

							writeCsvRow(out,

									rs.getString("GSTIN"), rs.getString("TRADE_NAME"), rs.getString("ST_JURI"),
									rs.getString("JURISDICTION_NAME"),

									rs.getString("RET_PERIOD"),

									periodDate == null ? "" : periodDate.toLocalDate().toString(),

									csvNumber(rs, "TAXABLE_VALUE"),

									csvNumber(rs, "OUTPUT_TAX"),

									csvNumber(rs, "ELIGIBLE_ITC"),

									csvNumber(rs, "UTILIZED_ITC"),

									csvNumber(rs, "CASH_TAX_PAID"),

									csvNumber(rs, "RCM_TOTAL_TAX"),

									csvNumber(rs, "MOM_TAXABLE_GROWTH"),

									csvNumber(rs, "MOM_OUTPUT_TAX_GROWTH"),

									csvNumber(rs, "MOM_ITC_GROWTH"),

									csvNumber(rs, "MOM_CASH_GROWTH"),

									csvNumber(rs, "YOY_TAXABLE_GROWTH"),

									csvNumber(rs, "YOY_OUTPUT_TAX_GROWTH"),

									csvNumber(rs, "YOY_ITC_GROWTH"),

									csvNumber(rs, "YOY_CASH_GROWTH"),

									rs.getObject("FILING_DELAY_DAYS") == null ? ""
											: Integer.toString(rs.getInt("FILING_DELAY_DAYS")),

									rs.getString("GROWTH_TREND"),

									csvNumber(rs, "RISK_SCORE"),

									rs.getString("RISK_LEVEL"));

							rows[0]++;

							if (rows[0] % 1000 == 0) {
								out.flush();
							}

						} catch (IOException ex) {

							throw new CsvWriteException(ex);
						}
					});

			out.flush();

			log.info("GST growth CSV completed period={} office={} trend={} risk={} rows={} durationMs={}", period,
					safeLog(office), safeLog(trend), safeLog(riskLevel), rows[0], elapsed(start));

		} catch (CsvWriteException ex) {

			log.error("CSV write failed while streaming growth export period={} office={} rows={} durationMs={}",
					period, safeLog(office), rows[0], elapsed(start), ex.getCause());

			throw new GrowthDataAccessException("CSV stream failed", ex.getCause());

		} catch (DataAccessException ex) {

			log.error("CSV database query failed period={} office={}", period, safeLog(office), ex);

			throw new GrowthDataAccessException("Failed to export growth data", ex);

		} catch (IOException ex) {

			log.error("CSV output stream failed period={} office={} rows={} durationMs={}", period, safeLog(office),
					rows[0], elapsed(start), ex);

			throw new GrowthDataAccessException("Failed to write CSV", ex);
		}
	}

	/*
	 * ======================================================== FILTER BUILDER
	 * ========================================================
	 */

	private FilterSql buildFilter(String period, String office, String search, String trend, String riskLevel) {

		StringBuilder where = new StringBuilder(" WHERE g.RET_PERIOD = ? ");

		List<Object> params = new ArrayList<>();

		params.add(period);

		if (hasText(office)) {

			where.append(" AND d.ST_JURI = ? ");

			params.add(office);
		}

		if (hasText(search)) {

			where.append("""

					AND (
					       UPPER(g.GSTIN) LIKE ?
					    OR UPPER(d.TRADE_NAME) LIKE ?
					)

					""");

			String pattern = "%" + search.trim().toUpperCase(Locale.ROOT) + "%";

			params.add(pattern);
			params.add(pattern);
		}

		if (hasText(trend)) {

			where.append(" AND UPPER(g.GROWTH_TREND) = ? ");

			params.add(trend.trim().toUpperCase(Locale.ROOT));
		}

		if (hasText(riskLevel)) {

			where.append(" AND UPPER(r.RISK_LEVEL) = ? ");

			params.add(riskLevel.trim().toUpperCase(Locale.ROOT));
		}

		return new FilterSql(where.toString(), List.copyOf(params));
	}

	/*
	 * ======================================================== MAPPER
	 * ========================================================
	 */

	private GrowthRowResponse mapGrowthRow(ResultSet rs, int rowNum) throws SQLException {

		Date date = rs.getDate("PERIOD_DATE");

		return GrowthRowResponse.builder()

				.gstin(rs.getString("GSTIN"))

				.tradeName(rs.getString("TRADE_NAME"))

				.stJuri(rs.getString("ST_JURI"))

				.officeName(rs.getString("JURISDICTION_NAME"))

				.retPeriod(rs.getString("RET_PERIOD"))

				.periodDate(date == null ? null : date.toLocalDate())

				.taxableValue(decimal(rs, "TAXABLE_VALUE"))

				.outputTax(decimal(rs, "OUTPUT_TAX"))

				.eligibleItc(decimal(rs, "ELIGIBLE_ITC"))

				.utilizedItc(decimal(rs, "UTILIZED_ITC"))

				.cashTaxPaid(decimal(rs, "CASH_TAX_PAID"))

				.rcmTotalTax(decimal(rs, "RCM_TOTAL_TAX"))

				.momTaxableGrowth(nullableDecimal(rs, "MOM_TAXABLE_GROWTH"))

				.momOutputTaxGrowth(nullableDecimal(rs, "MOM_OUTPUT_TAX_GROWTH"))

				.momItcGrowth(nullableDecimal(rs, "MOM_ITC_GROWTH"))

				.momCashGrowth(nullableDecimal(rs, "MOM_CASH_GROWTH"))

				.yoyTaxableGrowth(nullableDecimal(rs, "YOY_TAXABLE_GROWTH"))

				.yoyOutputTaxGrowth(nullableDecimal(rs, "YOY_OUTPUT_TAX_GROWTH"))

				.yoyItcGrowth(nullableDecimal(rs, "YOY_ITC_GROWTH"))

				.yoyCashGrowth(nullableDecimal(rs, "YOY_CASH_GROWTH"))

				.avg3mTaxable(nullableDecimal(rs, "AVG_3M_TAXABLE"))

				.avg6mTaxable(nullableDecimal(rs, "AVG_6M_TAXABLE"))

				.avg12mTaxable(nullableDecimal(rs, "AVG_12M_TAXABLE"))

				.avg3mOutputTax(nullableDecimal(rs, "AVG_3M_OUTPUT_TAX"))

				.avg6mOutputTax(nullableDecimal(rs, "AVG_6M_OUTPUT_TAX"))

				.avg12mOutputTax(nullableDecimal(rs, "AVG_12M_OUTPUT_TAX"))

				.avg3mItc(nullableDecimal(rs, "AVG_3M_ITC"))

				.avg6mItc(nullableDecimal(rs, "AVG_6M_ITC"))

				.avg12mItc(nullableDecimal(rs, "AVG_12M_ITC"))

				.filingDelayDays(nullableInteger(rs, "FILING_DELAY_DAYS"))

				.growthTrend(rs.getString("GROWTH_TREND"))

				/*
				 * Real risk engine output.
				 */
				.riskScore(nullableDecimal(rs, "RISK_SCORE"))

				.riskLevel(rs.getString("RISK_LEVEL"))

				.build();
	}

	/*
	 * ======================================================== EXPORT (NO RISK
	 * FILTER) ========================================================
	 */
	public void exportGrowthCsv(String period, String office, String search, String trend, String riskLevel,
			Writer writer) {

		FilterSql filter = buildFilter(period, office, search, trend, riskLevel);

		String sql = """
				SELECT
				    g.GSTIN,
				    d.TRADE_NAME,
				    d.ST_JURI,
				    j.JURISDICTION_NAME,

				    g.RET_PERIOD,
				    g.PERIOD_DATE,

				    g.TAXABLE_VALUE,
				    g.OUTPUT_TAX,
				    g.ELIGIBLE_ITC,
				    g.UTILIZED_ITC,
				    g.CASH_TAX_PAID,
				    g.RCM_TOTAL_TAX,

				    g.MOM_TAXABLE_GROWTH,
				    g.MOM_OUTPUT_TAX_GROWTH,
				    g.MOM_ITC_GROWTH,

				    g.YOY_TAXABLE_GROWTH,
				    g.YOY_OUTPUT_TAX_GROWTH,
				    g.YOY_ITC_GROWTH,

				    g.AVG_3M_TAXABLE,
				    g.AVG_6M_TAXABLE,
				    g.AVG_12M_TAXABLE,

				    g.FILING_DELAY_DAYS,
				    g.GROWTH_TREND

				""" + COMMON_FROM + filter.where() + """

				ORDER BY
				    j.JURISDICTION_NAME NULLS LAST,
				    d.TRADE_NAME NULLS LAST,
				    g.GSTIN
				""";

		long start = System.currentTimeMillis();

		final long[] rowCounter = { 0 };

		try {
			BufferedWriter bufferedWriter = writer instanceof BufferedWriter ? (BufferedWriter) writer
					: new BufferedWriter(writer);

			/*
			 * UTF-8 BOM. Helps Microsoft Excel correctly recognise UTF-8 CSV.
			 */
			bufferedWriter.write('\uFEFF');

			writeCsvRow(bufferedWriter, "GSTIN", "Trade Name", "ST Juri", "Office Name", "Return Period", "Period Date",
					"Taxable Value", "Output Tax", "Eligible ITC", "Utilized ITC", "Cash Tax Paid", "RCM Total Tax",
					"MoM Taxable Growth %", "MoM Output Tax Growth %", "MoM ITC Growth %", "YoY Taxable Growth %",
					"YoY Output Tax Growth %", "YoY ITC Growth %", "Avg 3M Taxable", "Avg 6M Taxable",
					"Avg 12M Taxable", "Filing Delay Days", "Growth Trend");

			jdbcTemplate.query(connection -> {

				var ps = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);

				int index = 1;

				for (Object parameter : filter.parameters()) {

					ps.setObject(index++, parameter);
				}

				/*
				 * JDBC hint only. Does NOT load 1000 rows into application memory.
				 */
				ps.setFetchSize(EXPORT_FETCH_SIZE);

				return ps;
			},

					(RowCallbackHandler) rs -> {

						try {
							writeCsvRow(bufferedWriter,

									rs.getString("GSTIN"), rs.getString("TRADE_NAME"), rs.getString("ST_JURI"),
									rs.getString("JURISDICTION_NAME"), rs.getString("RET_PERIOD"),

									rs.getDate("PERIOD_DATE") == null ? ""
											: rs.getDate("PERIOD_DATE").toLocalDate().toString(),

									csvNumber(rs, "TAXABLE_VALUE"), csvNumber(rs, "OUTPUT_TAX"),
									csvNumber(rs, "ELIGIBLE_ITC"), csvNumber(rs, "UTILIZED_ITC"),
									csvNumber(rs, "CASH_TAX_PAID"), csvNumber(rs, "RCM_TOTAL_TAX"),
									csvNumber(rs, "MOM_TAXABLE_GROWTH"), csvNumber(rs, "MOM_OUTPUT_TAX_GROWTH"),
									csvNumber(rs, "MOM_ITC_GROWTH"), csvNumber(rs, "YOY_TAXABLE_GROWTH"),
									csvNumber(rs, "YOY_OUTPUT_TAX_GROWTH"), csvNumber(rs, "YOY_ITC_GROWTH"),
									csvNumber(rs, "AVG_3M_TAXABLE"), csvNumber(rs, "AVG_6M_TAXABLE"),
									csvNumber(rs, "AVG_12M_TAXABLE"),

									rs.getObject("FILING_DELAY_DAYS") == null ? ""
											: String.valueOf(rs.getInt("FILING_DELAY_DAYS")),

									rs.getString("GROWTH_TREND"));

							rowCounter[0]++;

							/*
							 * Periodic flush allows the response to progressively reach the client.
							 */
							if (rowCounter[0] % 1000 == 0) {
								bufferedWriter.flush();
							}

						} catch (IOException ex) {
							throw new CsvWriteException(ex);
						}
					});

			bufferedWriter.flush();

			log.info("GST growth CSV export completed period={} office={} trend={} risk={} rows={} durationMs={}",
					period, safeLog(office), safeLog(trend), safeLog(riskLevel), rowCounter[0], elapsed(start));

		} catch (CsvWriteException ex) {

			log.error(
					"CSV write failed while streaming growth export (no-risk variant) period={} office={} rows={} durationMs={}",
					period, safeLog(office), rowCounter[0], elapsed(start), ex.getCause());

			throw new GrowthDataAccessException("Failed while streaming CSV export", ex.getCause());

		} catch (DataAccessException ex) {

			log.error("CSV database query failed (no-risk export variant) period={} office={} trend={} risk={}", period,
					safeLog(office), safeLog(trend), safeLog(riskLevel), ex);

			throw new GrowthDataAccessException("Failed to export GST growth data", ex);

		} catch (IOException ex) {

			log.error("CSV output stream failed (no-risk export variant) period={} office={} rows={} durationMs={}",
					period, safeLog(office), rowCounter[0], elapsed(start), ex);

			throw new GrowthDataAccessException("Failed to write CSV export", ex);
		}
	}

	private String csvNumber(ResultSet rs, String column) throws SQLException {

		BigDecimal value = rs.getBigDecimal(column);

		return value == null ? "" : value.toPlainString();
	}

	private void writeCsvRow(Writer writer, String... values) throws IOException {

		for (int i = 0; i < values.length; i++) {

			if (i > 0) {
				writer.write(',');
			}

			writer.write(escapeCsv(values[i]));
		}

		writer.write("\r\n");
	}

	private String escapeCsv(String value) {

		if (value == null) {
			return "";
		}

		/*
		 * Prevent CSV/Excel formula injection.
		 */
		String safe = value;

		if (!safe.isEmpty()) {

			char first = safe.charAt(0);

			if (first == '=' || first == '+' || first == '-' || first == '@') {

				safe = "'" + safe;
			}
		}

		boolean quote = safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r");

		safe = safe.replace("\"", "\"\"");

		return quote ? "\"" + safe + "\"" : safe;
	}

	/*
	 * ======================================================== UTILITIES
	 * ========================================================
	 */

	private BigDecimal decimal(ResultSet rs, String column) throws SQLException {

		BigDecimal value = rs.getBigDecimal(column);

		return value == null ? BigDecimal.ZERO : value;
	}

	private BigDecimal nullableDecimal(ResultSet rs, String column) throws SQLException {

		return rs.getBigDecimal(column);
	}

	private Integer nullableInteger(ResultSet rs, String column) throws SQLException {

		Object value = rs.getObject(column);

		return value == null ? null : ((Number) value).intValue();
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

		if (period == null || !period.matches("(0[1-9]|1[0-2])\\d{4}")) {

			return period;
		}

		String[] months = { "January", "February", "March", "April", "May", "June", "July", "August", "September",
				"October", "November", "December" };

		int month = Integer.parseInt(period.substring(0, 2));

		return months[month - 1] + " " + period.substring(2);
	}

	private record FilterSql(String where, List<Object> parameters) {
	}

	private static final class CsvWriteException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private CsvWriteException(Throwable cause) {

			super(cause);
		}
	}
}