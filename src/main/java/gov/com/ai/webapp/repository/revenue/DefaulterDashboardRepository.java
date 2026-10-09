package gov.com.ai.webapp.repository.revenue;

import gov.com.ai.webapp.exception.GrowthDataAccessException;
import gov.com.ai.webapp.model.revenue.DefaulterDashboardFilter;
import gov.com.ai.webapp.model.revenue.DefaulterDashboardRow;
import gov.com.ai.webapp.model.revenue.DefaulterDashboardSummary;
import gov.com.ai.webapp.model.revenue.OptionDto;
import gov.com.ai.webapp.model.revenue.PageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Slf4j
@Repository
@RequiredArgsConstructor
public class DefaulterDashboardRepository {

	private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("MMuuuu");

	private final NamedParameterJdbcTemplate jdbc;

	private static final String JURI_OFFICE_QUERY = " SELECT JURISDICTION_CODE, MERGED_JURISDICTION FROM  "
			+ " gst_master_juri_new ORDER BY JURISDICTION_CODE ";

	private static final String JURI_QUERY = " SELECT JURISDICTION_CODE, MERGED_JURISDICTION  FROM "
			+ " gst_master_juri_new WHERE charge_cd_vat = :officeId ";

	private static final String WHERE = " FROM GST_3B_RETURN_DEFAULTER d LEFT JOIN GST_DEALER_MASTER_WBCOMTAX r "
			+ " ON r.GSTIN = d.GSTIN  WHERE d.ACTIVE_FLAG = 'Y'"
			+ " AND d.RET_PERIOD = :retPeriod AND (:office IS NULL OR d.ST_JURI = :office) "
			+ "	AND (:filingStatus IS NULL OR d.FILING_STATUS = :filingStatus) "
			+ "	AND (:riskLevel IS NULL OR d.RISK_LEVEL = :riskLevel) "
			+ "	AND (:defaultLevel IS NULL OR d.DEFAULT_LEVEL = :defaultLevel) "
			+ "	AND (:gstr3aEligible IS NULL OR d.GSTR3A_ELIGIBLE = :gstr3aEligible) "
			+ "	AND (:search IS NULL OR UPPER(d.GSTIN) LIKE :searchLike OR UPPER(NVL(r.TRADE_NAME, '')) LIKE :searchLike "
			+ " OR UPPER(NVL(d.OFFICE_NAME, '')) LIKE :searchLike ) ";

	public DefaulterDashboardSummary summary(DefaulterDashboardFilter filter) {
		String sql = """
				SELECT
				    COUNT(*) TOTAL_COUNT,
				    NVL(SUM(CASE WHEN d.FILING_STATUS = 'NOT_FILED' THEN 1 ELSE 0 END), 0) NOT_FILED,
				    NVL(SUM(CASE WHEN d.FILING_STATUS = 'FILED_LATE' THEN 1 ELSE 0 END), 0) FILED_LATE,
				    NVL(SUM(CASE WHEN d.FILING_STATUS = 'FILED_ON_TIME' THEN 1 ELSE 0 END), 0) FILED_ON_TIME,
				    NVL(SUM(CASE WHEN d.FILING_STATUS = 'NOT_DUE' THEN 1 ELSE 0 END), 0) NOT_DUE,
				    NVL(SUM(CASE WHEN d.GSTR3A_ELIGIBLE = 'Y' THEN 1 ELSE 0 END), 0) GSTR3A_ELIGIBLE,
				    NVL(SUM(CASE WHEN d.GSTR3A_STATUS = 'ISSUED' THEN 1 ELSE 0 END), 0) GSTR3A_ISSUED,
				    NVL(SUM(CASE WHEN d.SECTION62_CANDIDATE = 'Y' THEN 1 ELSE 0 END), 0) SECTION62_COUNT,
				    NVL(SUM(CASE WHEN d.DEFAULT_LEVEL = 'CRITICAL' THEN 1 ELSE 0 END), 0) CRITICAL_COUNT,
				    NVL(SUM(CASE WHEN d.DEFAULT_LEVEL = 'HIGH' THEN 1 ELSE 0 END), 0) HIGH_COUNT,
				    NVL(SUM(CASE WHEN d.DEFAULT_LEVEL = 'WARNING' THEN 1 ELSE 0 END), 0) WARNING_COUNT,
				    NVL(SUM(CASE WHEN d.DEFAULT_LEVEL = 'NORMAL' THEN 1 ELSE 0 END), 0) NORMAL_COUNT,
				    NVL(SUM(d.OUTPUT_TAX), 0) TOTAL_OUTPUT_TAX,
				    NVL(SUM(d.TAXABLE_VALUE), 0) TOTAL_TAXABLE_VALUE
				""" + WHERE;

		return jdbc.queryForObject(sql, params(filter),
				(rs, rowNum) -> new DefaulterDashboardSummary(rs.getLong("TOTAL_COUNT"), rs.getLong("NOT_FILED"),
						rs.getLong("FILED_LATE"), rs.getLong("FILED_ON_TIME"), rs.getLong("NOT_DUE"),
						rs.getLong("GSTR3A_ELIGIBLE"), rs.getLong("GSTR3A_ISSUED"), rs.getLong("SECTION62_COUNT"),
						rs.getLong("CRITICAL_COUNT"), rs.getLong("HIGH_COUNT"), rs.getLong("WARNING_COUNT"),
						rs.getLong("NORMAL_COUNT"), nz(rs.getBigDecimal("TOTAL_OUTPUT_TAX")),
						nz(rs.getBigDecimal("TOTAL_TAXABLE_VALUE"))));
	}

	public PageResponse<DefaulterDashboardRow> page(DefaulterDashboardFilter filter) {
		MapSqlParameterSource params = params(filter);

		Long count = jdbc.queryForObject("SELECT COUNT(*) " + WHERE, params, Long.class);
		long total = count == null ? 0L : count;

		long startRow = (long) filter.page() * filter.size();
		long endRow = startRow + filter.size();

		params.addValue("startRow", startRow);
		params.addValue("endRow", endRow);

		String sql = """
				SELECT *
				FROM (
				    SELECT q.*, ROWNUM RN
				    FROM (
				        SELECT
				            d.GSTIN,
				            r.TRADE_NAME AS TRADE_NAME,
				            d.RET_PERIOD,
				            d.DUE_DATE,
				            d.FILING_STATUS,
				            d.FILING_DATE,
				            d.DELAY_DAYS,
				            d.TAXABLE_VALUE,
				            d.OUTPUT_TAX,
				            d.GROWTH_STATUS,
				            d.RISK_LEVEL,
				            d.RISK_SCORE,
				            d.DEFAULT_SCORE,
				            d.DEFAULT_LEVEL,
				            d.HISTORICAL_DEFAULT_COUNT,
				            d.GSTR3A_ELIGIBLE,
				            d.GSTR3A_STATUS,
				            d.GSTR3A_NOTICE_DATE,
				            d.GSTR3A_REFERENCE_NO,
				            d.RESPONSE_DUE_DATE,
				            d.RETURN_FILED_AFTER_NOTICE,
				            d.SECTION62_CANDIDATE,
				            d.OFFICER_REVIEW_STATUS,
				            d.ST_JURI,
				            d.OFFICE_NAME
				        """ + WHERE + """
				        ORDER BY
				            d.DEFAULT_SCORE DESC,
				            d.DELAY_DAYS DESC,
				            d.GSTIN ASC
				    ) q
				    WHERE ROWNUM <= :endRow
				)
				WHERE RN > :startRow
				""";

		List<DefaulterDashboardRow> rows = jdbc.query(sql, params,
				(rs, rowNum) -> new DefaulterDashboardRow(rs.getString("GSTIN"), rs.getString("TRADE_NAME"),
						rs.getString("RET_PERIOD"), date(rs.getDate("DUE_DATE")), rs.getString("FILING_STATUS"),
						date(rs.getDate("FILING_DATE")), rs.getInt("DELAY_DAYS"), nz(rs.getBigDecimal("TAXABLE_VALUE")),
						nz(rs.getBigDecimal("OUTPUT_TAX")), rs.getString("GROWTH_STATUS"), rs.getString("RISK_LEVEL"),
						nz(rs.getBigDecimal("RISK_SCORE")), nz(rs.getBigDecimal("DEFAULT_SCORE")),
						rs.getString("DEFAULT_LEVEL"), rs.getInt("HISTORICAL_DEFAULT_COUNT"),
						rs.getString("GSTR3A_ELIGIBLE"), rs.getString("GSTR3A_STATUS"),
						date(rs.getDate("GSTR3A_NOTICE_DATE")), rs.getString("GSTR3A_REFERENCE_NO"),
						date(rs.getDate("RESPONSE_DUE_DATE")), rs.getString("RETURN_FILED_AFTER_NOTICE"),
						rs.getString("SECTION62_CANDIDATE"), rs.getString("OFFICER_REVIEW_STATUS"),
						rs.getString("ST_JURI"), rs.getString("OFFICE_NAME")));

		return PageResponse.of(rows, filter.page(), filter.size(), total);
	}

	public List<OptionDto> periods() {
		String sql = """
				SELECT DISTINCT RET_PERIOD
				FROM GST_3B_RETURN_DEFAULTER
				ORDER BY TO_DATE(RET_PERIOD, 'MMYYYY') DESC
				""";

		return jdbc.getJdbcTemplate().query(sql, (rs, rowNum) -> {
			String value = rs.getString("RET_PERIOD");
			return new OptionDto(value, periodLabel(value));
		});
	}

	public List<OptionDto> offices(String retPeriod) {
		return jdbc.query(JURI_OFFICE_QUERY, (rs, rowNum) -> {
			String code = rs.getString("JURISDICTION_CODE");
			String officeName = rs.getString("MERGED_JURISDICTION");
			return new OptionDto(code, officeName == null || officeName.isBlank() ? code : officeName);
		});
	}

	public List<OptionDto> findChargeCdOffices(String officeId) {
		long start = System.currentTimeMillis();
		try {
			MapSqlParameterSource params = new MapSqlParameterSource("officeId", officeId);

			List<OptionDto> result = jdbc.query(JURI_QUERY, params, (rs,
					rowNum) -> new OptionDto(rs.getString("JURISDICTION_CODE"), rs.getString("MERGED_JURISDICTION")));

			log.debug("Loaded growth offices officeId={} count={} executionTime={}ms", officeId, result.size(),
					System.currentTimeMillis() - start);
			return result;

		} catch (DataAccessException ex) {
			log.error("Failed loading growth offices officeId={}", officeId, ex);
			throw new GrowthDataAccessException("Failed to load offices", ex);
		}
	}

	public void streamCsv(DefaulterDashboardFilter filter, Writer writer) throws IOException {
		String sql = """
				SELECT
				    d.GSTIN,
				    r.TRADE_NAME AS TRADE_NAME,
				    d.RET_PERIOD,
				    d.OFFICE_NAME,
				    d.ST_JURI,
				    d.FILING_STATUS,
				    d.DUE_DATE,
				    d.FILING_DATE,
				    d.DELAY_DAYS,
				    d.TAXABLE_VALUE,
				    d.OUTPUT_TAX,
				    d.GROWTH_STATUS,
				    d.RISK_LEVEL,
				    d.RISK_SCORE,
				    d.DEFAULT_LEVEL,
				    d.DEFAULT_SCORE,
				    d.HISTORICAL_DEFAULT_COUNT,
				    d.GSTR3A_ELIGIBLE,
				    d.GSTR3A_STATUS,
				    d.GSTR3A_NOTICE_DATE,
				    d.GSTR3A_REFERENCE_NO,
				    d.RESPONSE_DUE_DATE,
				    d.SECTION62_CANDIDATE,
				    d.OFFICER_REVIEW_STATUS
				""" + WHERE + """
				ORDER BY
				    d.DEFAULT_SCORE DESC,
				    d.DELAY_DAYS DESC,
				    d.GSTIN
				""";

		writer.write("GSTIN,Trade Name,Return Period,Office,Office Code,Filing Status,"
				+ "Due Date,Filing Date,Delay Days,Taxable Value,Output Tax,"
				+ "Growth Status,Risk Level,Risk Score,Default Level,Default Score,"
				+ "Historical Defaults,GSTR-3A Eligible,GSTR-3A Status,"
				+ "GSTR-3A Notice Date,GSTR-3A Reference,Response Due Date,"
				+ "Section 62 Candidate,Officer Review Status\r\n");

		try {
			jdbc.query(sql, params(filter), rs -> {
				try {
					writeCsvRow(writer, rs.getString("GSTIN"), rs.getString("TRADE_NAME"), rs.getString("RET_PERIOD"),
							rs.getString("OFFICE_NAME"), rs.getString("ST_JURI"), rs.getString("FILING_STATUS"),
							Objects.toString(rs.getDate("DUE_DATE"), ""),
							Objects.toString(rs.getDate("FILING_DATE"), ""), Integer.toString(rs.getInt("DELAY_DAYS")),
							Objects.toString(rs.getBigDecimal("TAXABLE_VALUE"), "0"),
							Objects.toString(rs.getBigDecimal("OUTPUT_TAX"), "0"), rs.getString("GROWTH_STATUS"),
							rs.getString("RISK_LEVEL"), Objects.toString(rs.getBigDecimal("RISK_SCORE"), "0"),
							rs.getString("DEFAULT_LEVEL"), Objects.toString(rs.getBigDecimal("DEFAULT_SCORE"), "0"),
							Integer.toString(rs.getInt("HISTORICAL_DEFAULT_COUNT")), rs.getString("GSTR3A_ELIGIBLE"),
							rs.getString("GSTR3A_STATUS"), Objects.toString(rs.getDate("GSTR3A_NOTICE_DATE"), ""),
							rs.getString("GSTR3A_REFERENCE_NO"), Objects.toString(rs.getDate("RESPONSE_DUE_DATE"), ""),
							rs.getString("SECTION62_CANDIDATE"), rs.getString("OFFICER_REVIEW_STATUS"));
				} catch (IOException ex) {
					throw new CsvStreamingException(ex);
				}
			});
		} catch (CsvStreamingException ex) {
			throw ex.ioException();
		}
	}

	private MapSqlParameterSource params(DefaulterDashboardFilter filter) {
		String search = clean(filter.search());

		return new MapSqlParameterSource().addValue("retPeriod", filter.retPeriod())
				.addValue("office", clean(filter.office())).addValue("filingStatus", clean(filter.filingStatus()))
				.addValue("riskLevel", clean(filter.riskLevel())).addValue("defaultLevel", clean(filter.defaultLevel()))
				.addValue("gstr3aEligible", clean(filter.gstr3aEligible())).addValue("search", search)
				.addValue("searchLike", search == null ? null : "%" + search.toUpperCase(Locale.ROOT) + "%");
	}

	private void writeCsvRow(Writer writer, String... values) throws IOException {
		for (int i = 0; i < values.length; i++) {
			if (i > 0) {
				writer.write(',');
			}
			writer.write(csv(values[i]));
		}
		writer.write("\r\n");
	}

	private String csv(String value) {
		if (value == null) {
			return "\"\"";
		}
		return "\"" + value.replace("\"", "\"\"") + "\"";
	}

	private String clean(String value) {
		if (value == null) {
			return null;
		}
		String v = value.trim();
		return v.isEmpty() ? null : v;
	}

	private BigDecimal nz(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

	private LocalDate date(Date value) {
		return value == null ? null : value.toLocalDate();
	}

	private String periodLabel(String period) {
		try {
			YearMonth ym = YearMonth.parse(period, PERIOD_FORMAT);
			return ym.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + ym.getYear();
		} catch (RuntimeException ex) {
			log.warn("Invalid return period encountered in dashboard table: {}", period);
			return period;
		}
	}

	private static final class CsvStreamingException extends RuntimeException {
		private static final long serialVersionUID = 1L;
		private final IOException ioException;

		private CsvStreamingException(IOException ioException) {
			super(ioException);
			this.ioException = ioException;
		}

		private IOException ioException() {
			return ioException;
		}
	}
}