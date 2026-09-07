package gov.com.ai.webapp.repository;

import static gov.com.ai.webapp.repository.QueryConstants.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import gov.com.ai.webapp.model.DealerMaster;
import gov.com.ai.webapp.model.GstRiskSummaryDto;
import gov.com.ai.webapp.model.Return3BSummaryBean;
import gov.com.ai.webapp.model.ReturnPeriodOptionDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Repository
@RequiredArgsConstructor
public class Return3bRepositoryImpl implements Return3bRepository {

	private final JdbcTemplate jdbcTemplate;

	private static final DateTimeFormatter INPUT_FORMATTER = DateTimeFormatter.ofPattern("MMyyyy");
	private static final DateTimeFormatter OUTPUT_FORMATTER = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

	/**
	 * Finds a registered dealer profile by GSTIN.
	 */
	@Transactional(readOnly = true)
	@Override
	public Optional<DealerMaster> findByGstin(String gstin) {
		if (gstin == null || gstin.isBlank()) {
			return Optional.empty();
		}
		try {
			DealerMaster dealer = jdbcTemplate.queryForObject(SQL_SELECT_DEALER, dealerRowMapper, gstin.trim());
			return Optional.ofNullable(dealer);
		} catch (EmptyResultDataAccessException e) {
			log.debug("Dealer profile not found for GSTIN: {}", gstin);
			return Optional.empty();
		} catch (DataAccessException e) {
			log.error("Database exception while querying dealer master for GSTIN: {}", gstin, e);
			throw e;
		}
	}

	/**
	 * RowMapper for mapping Dealer Master Records.
	 */
	private final RowMapper<DealerMaster> dealerRowMapper = (rs, rowNum) -> {
		DealerMaster d = new DealerMaster();
		d.setGstin(rs.getString("gstin"));
		d.setLegalName(rs.getString("legal_name"));
		d.setTradeName(rs.getString("trade_name"));
		d.setPanNo(rs.getString("pan_no"));
		d.setStJuri(rs.getString("st_juri"));
		d.setCtJuri(rs.getString("ct_juri"));
		d.setAuthStatus(rs.getString("auth_status"));
		return d;
	};

	/**
	 * Fetches the last N historical monthly returns for a given GSTIN ordered by
	 * return period DESC.
	 */
	@Transactional(readOnly = true)
	@Override
	public List<Return3BSummaryBean> findHistory(String gstin, int monthsLookback) {
		if (gstin == null || gstin.isBlank() || monthsLookback <= 0) {
			return Collections.emptyList();
		}
		try {
			return jdbcTemplate.query(FIND_HISTORY_LIMIT, rowMapper, gstin.trim(), monthsLookback);
		} catch (DataAccessException e) {
			log.error("Failed to retrieve 3B return history for GSTIN: {}", gstin, e);
			throw e;
		}
	}

	@Override
	public Optional<Return3BSummaryBean> findByGstinAndRetPeriod(String gstin, String retPeriod) {
		if (gstin == null || retPeriod == null) {
			return Optional.empty();
		}
		try {
			List<Return3BSummaryBean> results = jdbcTemplate.query(FIND_BY_GSTIN_PERIOD, rowMapper, gstin.trim(),
					retPeriod.trim());
			return results.stream().findFirst();
		} catch (DataAccessException e) {
			log.error("Error retrieving return for GSTIN: {} Period: {}", gstin, retPeriod, e);
			throw e;
		}
	}

	/**
	 * RowMapper to map Database ResultSet columns directly to Return3BSummaryBean.
	 */
	private final RowMapper<Return3BSummaryBean> rowMapper = (rs, rowNum) -> {
		Return3BSummaryBean bean = new Return3BSummaryBean();

		// Basic Info
		bean.setGstin(rs.getString("GSTIN"));
		bean.setRetPeriod(rs.getString("RET_PERIOD"));
		bean.setFilingDate(getLocalDate(rs, "FILING_DATE"));
		bean.setDueDate(getLocalDate(rs, "DUE_DATE"));
		bean.setFilingDelayDays(getInteger(rs, "FILING_DELAY_DAYS"));
		bean.setStateCode(rs.getString("STATE_CODE"));

		// Table 3.1(a) Outward Taxable
		bean.setTaxableValue(rs.getBigDecimal("TAXABLE_VALUE"));
		bean.setOutputIgst(rs.getBigDecimal("OUTPUT_IGST"));
		bean.setOutputCgst(rs.getBigDecimal("OUTPUT_CGST"));
		bean.setOutputSgst(rs.getBigDecimal("OUTPUT_SGST"));
		bean.setOutputCess(rs.getBigDecimal("OUTPUT_CESS"));
		bean.setTotalOutputTax(rs.getBigDecimal("TOTAL_OUTPUT_TAX"));

		// Table 3.1(b) Zero Rated
		bean.setZeroRatedValue(rs.getBigDecimal("ZERO_RATED_VALUE"));
		bean.setZeroRatedIgst(rs.getBigDecimal("ZERO_RATED_IGST"));
		bean.setZeroRatedCgst(rs.getBigDecimal("ZERO_RATED_CGST"));
		bean.setZeroRatedSgst(rs.getBigDecimal("ZERO_RATED_SGST"));
		bean.setZeroRatedCess(rs.getBigDecimal("ZERO_RATED_CESS"));

		// Table 3.1(c) Nil/Exempt
		bean.setNilExemptValue(rs.getBigDecimal("NIL_EXEMPT_VALUE"));
		bean.setNilExemptIgst(rs.getBigDecimal("NIL_EXEMPT_IGST"));
		bean.setNilExemptCgst(rs.getBigDecimal("NIL_EXEMPT_CGST"));
		bean.setNilExemptSgst(rs.getBigDecimal("NIL_EXEMPT_SGST"));
		bean.setNilExemptCess(rs.getBigDecimal("NIL_EXEMPT_CESS"));

		// Table 3.1(d) Reverse Charge
		bean.setRcmTaxableValue(rs.getBigDecimal("RCM_TAXABLE_VALUE"));
		bean.setRcmIgst(rs.getBigDecimal("RCM_IGST"));
		bean.setRcmCgst(rs.getBigDecimal("RCM_CGST"));
		bean.setRcmSgst(rs.getBigDecimal("RCM_SGST"));
		bean.setRcmCess(rs.getBigDecimal("RCM_CESS"));
		bean.setRcmTotalTax(rs.getBigDecimal("RCM_TOTAL_TAX"));

		// Table 3.1(e) Non-GST
		bean.setNonGstValue(rs.getBigDecimal("NON_GST_VALUE"));
		bean.setNonGstIgst(rs.getBigDecimal("NON_GST_IGST"));
		bean.setNonGstCgst(rs.getBigDecimal("NON_GST_CGST"));
		bean.setNonGstSgst(rs.getBigDecimal("NON_GST_SGST"));
		bean.setNonGstCess(rs.getBigDecimal("NON_GST_CESS"));

		// ITC Import Goods & Services
		bean.setItcImportGoodsIgst(rs.getBigDecimal("ITC_IMPORT_GOODS_IGST"));
		bean.setItcImportGoodsCgst(rs.getBigDecimal("ITC_IMPORT_GOODS_CGST"));
		bean.setItcImportGoodsSgst(rs.getBigDecimal("ITC_IMPORT_GOODS_SGST"));
		bean.setItcImportGoodsCess(rs.getBigDecimal("ITC_IMPORT_GOODS_CESS"));

		bean.setItcIsrcIgst(rs.getBigDecimal("ITC_ISRC_IGST"));
		bean.setItcIsrcCgst(rs.getBigDecimal("ITC_ISRC_CGST"));
		bean.setItcIsrcSgst(rs.getBigDecimal("ITC_ISRC_SGST"));
		bean.setItcIsrcCess(rs.getBigDecimal("ITC_ISRC_CESS"));

		bean.setItcOthIgst(rs.getBigDecimal("ITC_OTH_IGST"));
		bean.setItcOthCgst(rs.getBigDecimal("ITC_OTH_CGST"));
		bean.setItcOthSgst(rs.getBigDecimal("ITC_OTH_SGST"));
		bean.setItcOthCess(rs.getBigDecimal("ITC_OTH_CESS"));

		bean.setItcIsdIgst(rs.getBigDecimal("ITC_ISD_IGST"));
		bean.setItcIsdCgst(rs.getBigDecimal("ITC_ISD_CGST"));
		bean.setItcIsdSgst(rs.getBigDecimal("ITC_ISD_SGST"));
		bean.setItcIsdCess(rs.getBigDecimal("ITC_ISD_CESS"));

		bean.setItcImpsIgst(rs.getBigDecimal("ITC_IMPS_IGST"));
		bean.setItcImpsCgst(rs.getBigDecimal("ITC_IMPS_CGST"));
		bean.setItcImpsSgst(rs.getBigDecimal("ITC_IMPS_SGST"));
		bean.setItcImpsCess(rs.getBigDecimal("ITC_IMPS_CESS"));

		// ITC Reversals & Net ITC
		bean.setItcRevRulIgst(rs.getBigDecimal("ITC_REV_RUL_IGST"));
		bean.setItcRevRulCgst(rs.getBigDecimal("ITC_REV_RUL_CGST"));
		bean.setItcRevRulSgst(rs.getBigDecimal("ITC_REV_RUL_SGST"));
		bean.setItcRevRulCess(rs.getBigDecimal("ITC_REV_RUL_CESS"));

		bean.setItcRevOthIgst(rs.getBigDecimal("ITC_REV_OTH_IGST"));
		bean.setItcRevOthCgst(rs.getBigDecimal("ITC_REV_OTH_CGST"));
		bean.setItcRevOthSgst(rs.getBigDecimal("ITC_REV_OTH_SGST"));
		bean.setItcRevOthCess(rs.getBigDecimal("ITC_REV_OTH_CESS"));

		bean.setNetItcIgst(rs.getBigDecimal("NET_ITC_IGST"));
		bean.setNetItcCgst(rs.getBigDecimal("NET_ITC_CGST"));
		bean.setNetItcSgst(rs.getBigDecimal("NET_ITC_SGST"));
		bean.setNetItcCess(rs.getBigDecimal("NET_ITC_CESS"));
		bean.setNetItcTotal(rs.getBigDecimal("NET_ITC_TOTAL"));

		// Ineligible & Aggregated ITC
		bean.setIneligibleItcRulIgst(rs.getBigDecimal("INELIGIBLE_ITC_RUL_IGST"));
		bean.setIneligibleItcRulCgst(rs.getBigDecimal("INELIGIBLE_ITC_RUL_CGST"));
		bean.setIneligibleItcRulSgst(rs.getBigDecimal("INELIGIBLE_ITC_RUL_SGST"));
		bean.setIneligibleItcRulCess(rs.getBigDecimal("INELIGIBLE_ITC_RUL_CESS"));

		bean.setIneligibleItcOthIgst(rs.getBigDecimal("INELIGIBLE_ITC_OTH_IGST"));
		bean.setIneligibleItcOthCgst(rs.getBigDecimal("INELIGIBLE_ITC_OTH_CGST"));
		bean.setIneligibleItcOthSgst(rs.getBigDecimal("INELIGIBLE_ITC_OTH_SGST"));
		bean.setIneligibleItcOthCess(rs.getBigDecimal("INELIGIBLE_ITC_OTH_CESS"));

		bean.setEligibleItc(rs.getBigDecimal("ELIGIBLE_ITC"));
		bean.setUtilizedItc(rs.getBigDecimal("UTILIZED_ITC"));
		bean.setReversedItc(rs.getBigDecimal("REVERSED_ITC"));
		bean.setIneligibleItc(rs.getBigDecimal("INELIGIBLE_ITC"));
		bean.setExcessItc(rs.getBigDecimal("EXCESS_ITC"));

		// Payments
		bean.setRcmPaymentIgst(rs.getBigDecimal("RCM_PAYMENT_IGST"));
		bean.setRcmPaymentCgst(rs.getBigDecimal("RCM_PAYMENT_CGST"));
		bean.setRcmPaymentSgst(rs.getBigDecimal("RCM_PAYMENT_SGST"));
		bean.setRcmPaymentCess(rs.getBigDecimal("RCM_PAYMENT_CESS"));
		bean.setRcmPaymentTotal(rs.getBigDecimal("RCM_PAYMENT_TOTAL"));

		bean.setCashIgstPaid(rs.getBigDecimal("CASH_IGST_PAID"));
		bean.setCashCgstPaid(rs.getBigDecimal("CASH_CGST_PAID"));
		bean.setCashSgstPaid(rs.getBigDecimal("CASH_SGST_PAID"));
		bean.setCashCessPaid(rs.getBigDecimal("CASH_CESS_PAID"));
		bean.setCashTaxPaid(rs.getBigDecimal("CASH_TAX_PAID"));

		bean.setItcPaymentIgst(rs.getBigDecimal("ITC_PAYMENT_IGST"));
		bean.setItcPaymentCgst(rs.getBigDecimal("ITC_PAYMENT_CGST"));
		bean.setItcPaymentSgst(rs.getBigDecimal("ITC_PAYMENT_SGST"));
		bean.setItcPaymentCess(rs.getBigDecimal("ITC_PAYMENT_CESS"));
		bean.setItcPaymentTotal(rs.getBigDecimal("ITC_PAYMENT_TOTAL"));

		// Interest, Late Fees, and Flags
		bean.setInterestIgst(rs.getBigDecimal("INTEREST_IGST"));
		bean.setInterestCgst(rs.getBigDecimal("INTEREST_CGST"));
		bean.setInterestSgst(rs.getBigDecimal("INTEREST_SGST"));
		bean.setInterestCess(rs.getBigDecimal("INTEREST_CESS"));
		bean.setInterestPaid(rs.getBigDecimal("INTEREST_PAID"));

		bean.setLateFeeIgst(rs.getBigDecimal("LATE_FEE_IGST"));
		bean.setLateFeeCgst(rs.getBigDecimal("LATE_FEE_CGST"));
		bean.setLateFeeSgst(rs.getBigDecimal("LATE_FEE_SGST"));
		bean.setLateFeeCess(rs.getBigDecimal("LATE_FEE_CESS"));
		bean.setLateFeePaid(rs.getBigDecimal("LATE_FEE_PAID"));

		bean.setLateFeeApplicable(getInteger(rs, "LATE_FEE_APPLICABLE"));
		bean.setCalculatedInterest(rs.getBigDecimal("CALCULATED_INTEREST"));
		bean.setInterestApplicable(getInteger(rs, "INTEREST_APPLICABLE"));

		// E-Commerce
		bean.setEcommerceTurnover(rs.getBigDecimal("ECOMMERCE_TURNOVER"));
		bean.setEcommerceIgst(rs.getBigDecimal("ECOMMERCE_IGST"));
		bean.setEcommerceCgst(rs.getBigDecimal("ECOMMERCE_CGST"));
		bean.setEcommerceSgst(rs.getBigDecimal("ECOMMERCE_SGST"));
		bean.setEcommerceCess(rs.getBigDecimal("ECOMMERCE_CESS"));

		bean.setEcommerceRegisteredTurnover(rs.getBigDecimal("ECOMMERCE_REGISTERED_TURNOVER"));
		bean.setEcommerceRegisteredIgst(rs.getBigDecimal("ECOMMERCE_REGISTERED_IGST"));
		bean.setEcommerceRegisteredCgst(rs.getBigDecimal("ECOMMERCE_REGISTERED_CGST"));
		bean.setEcommerceRegisteredSgst(rs.getBigDecimal("ECOMMERCE_REGISTERED_SGST"));
		bean.setEcommerceRegisteredCess(rs.getBigDecimal("ECOMMERCE_REGISTERED_CESS"));

		// Derived Ratios
		bean.setNilSupplyRatio(rs.getBigDecimal("NIL_SUPPLY_RATIO"));
		bean.setItcUtilizationRatio(rs.getBigDecimal("ITC_UTILIZATION_RATIO"));
		bean.setItcToTaxRatio(rs.getBigDecimal("ITC_TO_TAX_RATIO"));
		bean.setCashPaymentRatio(rs.getBigDecimal("CASH_PAYMENT_RATIO"));
		bean.setItcPaymentRatio(rs.getBigDecimal("ITC_PAYMENT_RATIO"));
		bean.setRcmToTaxRatio(rs.getBigDecimal("RCM_TO_TAX_RATIO"));
		bean.setRcmItcRatio(rs.getBigDecimal("RCM_ITC_RATIO"));
		bean.setRcmCashRatio(rs.getBigDecimal("RCM_CASH_RATIO"));

		// Questionnaire
		bean.setQ1(rs.getString("Q1"));
		bean.setQ2(rs.getString("Q2"));
		bean.setQ3(rs.getString("Q3"));
		bean.setQ4(rs.getString("Q4"));
		bean.setQ5(rs.getString("Q5"));
		bean.setQ6(rs.getString("Q6"));
		bean.setQ7(rs.getString("Q7"));

		// AI Risk Scores
		bean.setXgbRiskScore(rs.getBigDecimal("XGB_RISK_SCORE"));
		bean.setDl4jAnomalyScore(rs.getBigDecimal("DL4J_ANOMALY_SCORE"));

		return bean;
	};

	private LocalDate getLocalDate(ResultSet rs, String columnName) throws SQLException {
		java.sql.Date date = rs.getDate(columnName);
		return date != null ? date.toLocalDate() : null;
	}

	private Integer getInteger(ResultSet rs, String columnName) throws SQLException {
		int value = rs.getInt(columnName);
		return rs.wasNull() ? null : value;
	}

	@Override
	public List<ReturnPeriodOptionDto> getAllAvailableReturnPeriods() {
		return jdbcTemplate.query(FETCH_RETURN_PERIOD, (rs, rowNum) -> {
			String rawPeriod = rs.getString("ret_period");
			YearMonth yearMonth = YearMonth.parse(rawPeriod, INPUT_FORMATTER);
			String formattedLabel = yearMonth.format(OUTPUT_FORMATTER);
			return ReturnPeriodOptionDto.builder().value(rawPeriod).label(formattedLabel).build();
		});
	}

	@Override
	public List<GstRiskSummaryDto> getRiskSummary(String retPeriod) {
		return jdbcTemplate.query(FETCH_RISK_SUMMARY, (rs, rowNum) -> GstRiskSummaryDto.builder()
				.gstin(rs.getString("GSTIN"))
				.retPeriod(rs.getString("RET_PERIOD"))
				.taxableValue(rs.getBigDecimal("TAXABLE_VALUE"))
				.totalOutputTax(rs.getBigDecimal("TOTAL_OUTPUT_TAX"))
				.eligibleItc(rs.getBigDecimal("ELIGIBLE_ITC"))
				.utilizedItc(rs.getBigDecimal("UTILIZED_ITC"))
				.excessItc(rs.getBigDecimal("EXCESS_ITC"))
				.cashTaxPaid(rs.getBigDecimal("CASH_TAX_PAID"))
				.itcUtilizationRatio(rs.getObject("ITC_UTILIZATION_RATIO") != null ? rs.getDouble("ITC_UTILIZATION_RATIO") : null)
				.cashPaymentRatio(rs.getObject("CASH_PAYMENT_RATIO") != null ? rs.getDouble("CASH_PAYMENT_RATIO") : null)
				.filingDelayDays(rs.getObject("FILING_DELAY_DAYS") != null ? rs.getInt("FILING_DELAY_DAYS") : null)
				.xgbRiskScore(rs.getObject("XGB_RISK_SCORE") != null ? rs.getDouble("XGB_RISK_SCORE") : null)
				.dl4jAnomalyScore(rs.getObject("DL4J_ANOMALY_SCORE") != null ? rs.getDouble("DL4J_ANOMALY_SCORE") : null)
				.build(), retPeriod);
	}
}
