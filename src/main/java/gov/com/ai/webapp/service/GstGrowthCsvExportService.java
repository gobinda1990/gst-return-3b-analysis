//package gov.com.ai.webapp.service;
//
//import java.io.BufferedWriter;
//import java.io.IOException;
//import java.io.OutputStream;
//import java.io.OutputStreamWriter;
//import java.io.UncheckedIOException;
//import java.math.BigDecimal;
//import java.nio.charset.StandardCharsets;
//import java.sql.ResultSet;
//import java.sql.SQLException;
//import java.util.Objects;
//
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.jdbc.core.JdbcTemplate;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.support.TransactionTemplate;
//
//@Service
//public class GstGrowthCsvExportService {
//
//	private static final Logger log = LoggerFactory.getLogger(GstGrowthCsvExportService.class);
//
//	private static final int FETCH_SIZE = 500;
//
//	private static final String SQL = """
//			SELECT
//			    GSTIN,
//			    RET_PERIOD,
//			    TAXABLE_VALUE,
//			    OUTPUT_TAX,
//			    ELIGIBLE_ITC,
//			    UTILIZED_ITC,
//			    CASH_TAX_PAID,
//			    RCM_TOTAL_TAX,
//			    MOM_TAXABLE_GROWTH,
//			    MOM_OUTPUT_TAX_GROWTH,
//			    MOM_ITC_GROWTH
//			FROM GST_3B_GROWTH_ANALYTICS
//			WHERE RET_PERIOD = ?
//			ORDER BY GSTIN
//			""";
//
//	private static final String[] HEADERS = { "GSTIN", "Return Period", "Taxable Value", "Output Tax", "Eligible ITC",
//			"Utilized ITC", "Cash Tax Paid", "RCM Total Tax", "MoM Taxable Growth (%)", "MoM Output Tax Growth (%)",
//			"MoM ITC Growth (%)" };
//
//	private final JdbcTemplate jdbcTemplate;
//	private final TransactionTemplate readOnlyTransaction;
//
//	public GstGrowthCsvExportService(JdbcTemplate jdbcTemplate,
//			org.springframework.transaction.PlatformTransactionManager transactionManager) {
//
//		this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
//
//		this.readOnlyTransaction = new TransactionTemplate(
//				Objects.requireNonNull(transactionManager, "transactionManager"));
//
//		this.readOnlyTransaction.setReadOnly(true);
//	}
//
//	public void writeCsv(String period, OutputStream outputStream) throws IOException {
//
//		Objects.requireNonNull(outputStream, "outputStream");
//
//		// Defense in depth; the controller also validates this parameter.
//		if (period == null || !period.matches("(0[1-9]|1[0-2])\\d{4}")) {
//			throw new IllegalArgumentException("Invalid return period");
//		}
//
//		BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8),
//				64 * 1024);
//
//		try {
//			// Excel recognizes UTF-8 reliably with a BOM.
//			writer.write('\uFEFF');
//			writeRecord(writer, HEADERS);
//
//			readOnlyTransaction.executeWithoutResult(status -> jdbcTemplate.query(connection -> {
//				var statement = connection.prepareStatement(SQL);
//
//				statement.setString(1, period);
//				statement.setFetchSize(FETCH_SIZE);
//				statement.setQueryTimeout(300);
//
//				return statement;
//			}, resultSet -> writeRow(writer, resultSet)));
//
//			writer.flush();
//			log.info("GST 3B growth CSV exported for period={}", period);
//
//		} catch (UncheckedIOException exception) {
//			throw exception.getCause();
//		}
//	}
//
//	private static void writeRow(BufferedWriter writer, ResultSet rs) throws SQLException {
//
//		String[] values = { rs.getString("GSTIN"), rs.getString("RET_PERIOD"), decimal(rs, "TAXABLE_VALUE"),
//				decimal(rs, "OUTPUT_TAX"), decimal(rs, "ELIGIBLE_ITC"), decimal(rs, "UTILIZED_ITC"),
//				decimal(rs, "CASH_TAX_PAID"), decimal(rs, "RCM_TOTAL_TAX"), decimal(rs, "MOM_TAXABLE_GROWTH"),
//				decimal(rs, "MOM_OUTPUT_TAX_GROWTH"), decimal(rs, "MOM_ITC_GROWTH") };
//
//		try {
//			writeRecord(writer, values);
//		} catch (IOException exception) {
//			throw new UncheckedIOException(exception);
//		}
//	}
//
//	private static String decimal(ResultSet rs, String column) throws SQLException {
//
//		BigDecimal value = rs.getBigDecimal(column);
//		return value == null ? "" : value.toPlainString();
//	}
//
//	private static void writeRecord(BufferedWriter writer, String[] values) throws IOException {
//
//		for (int index = 0; index < values.length; index++) {
//			if (index > 0)
//				writer.write(',');
//
//			String value = values[index] == null ? "" : values[index];
//
//			// Prevent spreadsheet formula execution if a data column
//			// later contains untrusted text.
//			if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
//				value = "'" + value;
//			}
//
//			writer.write('"');
//			writer.write(value.replace("\"", "\"\""));
//			writer.write('"');
//		}
//
//		writer.write("\r\n");
//	}
//}