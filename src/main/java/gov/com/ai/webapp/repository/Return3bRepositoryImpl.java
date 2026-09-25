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
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import gov.com.ai.webapp.exception.RepositoryException;
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
//	private static final int DEFAULT_QUERY_TIMEOUT = 30;
//	private static final int MAX_RETURN_PERIODS = 500;
	private static final int RETRY_ATTEMPTS = 3;
	private static final long RETRY_DELAY_MS = 1000L;

	/* ============================================================
	   DEALER MASTER QUERIES
	   ============================================================ */

	/**
	 * Finds a registered dealer profile by GSTIN.
	 * Uses retry mechanism for transient database errors.
	 */
	@Transactional(readOnly = true)
	@Retryable(
		retryFor = {RecoverableDataAccessException.class},
		maxAttempts = RETRY_ATTEMPTS,
		backoff = @Backoff(delay = RETRY_DELAY_MS)
	)
	@Override
	public Optional<DealerMaster> findByGstin(String gstin) {
		if (gstin == null || gstin.isBlank()) {
			log.warn("findByGstin called with null or empty GSTIN");
			return Optional.empty();
		}

		String normalizedGstin = gstin.trim().toUpperCase();

		try {
			DealerMaster dealer = jdbcTemplate.queryForObject(
				SQL_SELECT_DEALER,
				dealerRowMapper,
				normalizedGstin
			);
			log.debug("Dealer profile found for GSTIN: {}", normalizedGstin);
			return Optional.ofNullable(dealer);

		} catch (EmptyResultDataAccessException e) {
			log.debug("Dealer profile not found for GSTIN: {}", normalizedGstin);
			return Optional.empty();

		} catch (RecoverableDataAccessException e) {
			log.warn("Recoverable database error while querying dealer master for GSTIN: {}. Retrying...", 
				normalizedGstin, e);
			throw e; // Trigger retry

		} catch (DataAccessException e) {
			log.error("Database exception while querying dealer master for GSTIN: {}", normalizedGstin, e);
			throw new RepositoryException("Failed to retrieve dealer profile for GSTIN: " + normalizedGstin);
		}
	}

	/**
	 * RowMapper for Dealer Master Records with null safety.
	 */
	private final RowMapper<DealerMaster> dealerRowMapper = (rs, rowNum) -> {
		try {
			DealerMaster d = new DealerMaster();
			d.setGstin(getNullableString(rs, "gstin"));
			d.setLegalName(getNullableString(rs, "legal_name"));
			d.setTradeName(getNullableString(rs, "trade_name"));
			d.setPanNo(getNullableString(rs, "pan_no")); // ← FIX: Use setPan(), not setPanNo()
			d.setStJuri(getNullableString(rs, "st_juri"));
			d.setCtJuri(getNullableString(rs, "ct_juri"));
			d.setAuthStatus(getNullableString(rs, "auth_status"));
			return d;
		} catch (SQLException e) {
			log.error("Error mapping dealer record from ResultSet", e);
			throw new RepositoryException("Failed to map dealer record");
		}
	};

	/* ============================================================
	   GSTR-3B RETURN QUERIES
	   ============================================================ */

	/**
	 * Fetches the last N historical monthly returns for a given GSTIN.
	 * Includes retry mechanism for transient failures.
	 */
	@Transactional(readOnly = true)
	@Retryable(
		retryFor = {RecoverableDataAccessException.class},
		maxAttempts = RETRY_ATTEMPTS,
		backoff = @Backoff(delay = RETRY_DELAY_MS)
	)
	@Override
	public List<Return3BSummaryBean> findHistory(String gstin, int monthsLookback) {
		if (gstin == null || gstin.isBlank() || monthsLookback <= 0) {
			log.warn("findHistory called with invalid parameters. gstin={}, monthsLookback={}", gstin, monthsLookback);
			return Collections.emptyList();
		}

		String normalizedGstin = gstin.trim().toUpperCase();
		int safeLookback = Math.min(Math.max(monthsLookback, 1), 120); // Cap between 1-120 months

		try {
			List<Return3BSummaryBean> results = jdbcTemplate.query(
				FIND_HISTORY_LIMIT,
				rowMapper,
				normalizedGstin,
				safeLookback
			);
			log.debug("Retrieved {} return records for GSTIN: {} with lookback={} months", 
				results.size(), normalizedGstin, safeLookback);
			return results;

		} catch (RecoverableDataAccessException e) {
			log.warn("Recoverable error retrieving history for GSTIN: {}. Retrying...", normalizedGstin, e);
			throw e; // Trigger retry

		} catch (DataAccessException e) {
			log.error("Failed to retrieve 3B return history for GSTIN: {}", normalizedGstin, e);
			throw new RepositoryException("Failed to retrieve return history for GSTIN: " + normalizedGstin);
		}
	}

	/**
	 * Finds a specific return by GSTIN and return period.
	 */
	@Transactional(readOnly = true)
	@Retryable(
		retryFor = {RecoverableDataAccessException.class},
		maxAttempts = RETRY_ATTEMPTS,
		backoff = @Backoff(delay = RETRY_DELAY_MS)
	)
	@Override
	public Optional<Return3BSummaryBean> findByGstinAndRetPeriod(String gstin, String retPeriod) {
		if (gstin == null || gstin.isBlank() || retPeriod == null || retPeriod.isBlank()) {
			log.warn("findByGstinAndRetPeriod called with null parameters. gstin={}, retPeriod={}", gstin, retPeriod);
			return Optional.empty();
		}

		String normalizedGstin = gstin.trim().toUpperCase();
		String normalizedPeriod = retPeriod.trim();

		try {
			List<Return3BSummaryBean> results = jdbcTemplate.query(
				FIND_BY_GSTIN_PERIOD,
				rowMapper,
				normalizedGstin,
				normalizedPeriod
			);
			log.debug("Retrieved return for GSTIN: {} Period: {}", normalizedGstin, normalizedPeriod);
			return results.stream().findFirst();

		} catch (RecoverableDataAccessException e) {
			log.warn("Recoverable error retrieving return for GSTIN: {} Period: {}. Retrying...", 
				normalizedGstin, normalizedPeriod, e);
			throw e; // Trigger retry

		} catch (DataAccessException e) {
			log.error("Error retrieving return for GSTIN: {} Period: {}", normalizedGstin, normalizedPeriod, e);
			throw new RepositoryException("Failed to retrieve return for GSTIN:");
		}
	}

	/**
	 * RowMapper with comprehensive null safety for all BigDecimal and Integer fields.
	 */
	private final RowMapper<Return3BSummaryBean> rowMapper = (rs, rowNum) -> {
		try {
			Return3BSummaryBean bean = new Return3BSummaryBean();

			// Basic Info
			bean.setGstin(getNullableString(rs, "GSTIN"));
			bean.setRetPeriod(getNullableString(rs, "RET_PERIOD"));
			bean.setFilingDate(getLocalDate(rs, "FILING_DATE"));
			bean.setDueDate(getLocalDate(rs, "DUE_DATE"));
			bean.setFilingDelayDays(getNullableInteger(rs, "FILING_DELAY_DAYS"));
			bean.setStateCode(getNullableString(rs, "STATE_CODE"));

			// Table 3.1(a) - Outward Taxable
			bean.setTaxableValue(getNullableBigDecimal(rs, "TAXABLE_VALUE"));
			bean.setOutputIgst(getNullableBigDecimal(rs, "OUTPUT_IGST"));
			bean.setOutputCgst(getNullableBigDecimal(rs, "OUTPUT_CGST"));
			bean.setOutputSgst(getNullableBigDecimal(rs, "OUTPUT_SGST"));
			bean.setOutputCess(getNullableBigDecimal(rs, "OUTPUT_CESS"));
			bean.setTotalOutputTax(getNullableBigDecimal(rs, "TOTAL_OUTPUT_TAX"));

			// Table 3.1(b) - Zero Rated
			bean.setZeroRatedValue(getNullableBigDecimal(rs, "ZERO_RATED_VALUE"));
			bean.setZeroRatedIgst(getNullableBigDecimal(rs, "ZERO_RATED_IGST"));
			bean.setZeroRatedCgst(getNullableBigDecimal(rs, "ZERO_RATED_CGST"));
			bean.setZeroRatedSgst(getNullableBigDecimal(rs, "ZERO_RATED_SGST"));
			bean.setZeroRatedCess(getNullableBigDecimal(rs, "ZERO_RATED_CESS"));

			// Table 3.1(c) - Nil/Exempt
			bean.setNilExemptValue(getNullableBigDecimal(rs, "NIL_EXEMPT_VALUE"));
			bean.setNilExemptIgst(getNullableBigDecimal(rs, "NIL_EXEMPT_IGST"));
			bean.setNilExemptCgst(getNullableBigDecimal(rs, "NIL_EXEMPT_CGST"));
			bean.setNilExemptSgst(getNullableBigDecimal(rs, "NIL_EXEMPT_SGST"));
			bean.setNilExemptCess(getNullableBigDecimal(rs, "NIL_EXEMPT_CESS"));

			// Table 3.1(d) - Reverse Charge (RCM)
			bean.setRcmTaxableValue(getNullableBigDecimal(rs, "RCM_TAXABLE_VALUE"));
			bean.setRcmIgst(getNullableBigDecimal(rs, "RCM_IGST"));
			bean.setRcmCgst(getNullableBigDecimal(rs, "RCM_CGST"));
			bean.setRcmSgst(getNullableBigDecimal(rs, "RCM_SGST"));
			bean.setRcmCess(getNullableBigDecimal(rs, "RCM_CESS"));
			bean.setRcmTotalTax(getNullableBigDecimal(rs, "RCM_TOTAL_TAX"));

			// Table 3.1(e) - Non-GST
			bean.setNonGstValue(getNullableBigDecimal(rs, "NON_GST_VALUE"));
			bean.setNonGstIgst(getNullableBigDecimal(rs, "NON_GST_IGST"));
			bean.setNonGstCgst(getNullableBigDecimal(rs, "NON_GST_CGST"));
			bean.setNonGstSgst(getNullableBigDecimal(rs, "NON_GST_SGST"));
			bean.setNonGstCess(getNullableBigDecimal(rs, "NON_GST_CESS"));

			// ITC Import Goods & Services
			bean.setItcImportGoodsIgst(getNullableBigDecimal(rs, "ITC_IMPORT_GOODS_IGST"));
			bean.setItcImportGoodsCgst(getNullableBigDecimal(rs, "ITC_IMPORT_GOODS_CGST"));
			bean.setItcImportGoodsSgst(getNullableBigDecimal(rs, "ITC_IMPORT_GOODS_SGST"));
			bean.setItcImportGoodsCess(getNullableBigDecimal(rs, "ITC_IMPORT_GOODS_CESS"));

			bean.setItcIsrcIgst(getNullableBigDecimal(rs, "ITC_ISRC_IGST"));
			bean.setItcIsrcCgst(getNullableBigDecimal(rs, "ITC_ISRC_CGST"));
			bean.setItcIsrcSgst(getNullableBigDecimal(rs, "ITC_ISRC_SGST"));
			bean.setItcIsrcCess(getNullableBigDecimal(rs, "ITC_ISRC_CESS"));

			bean.setItcOthIgst(getNullableBigDecimal(rs, "ITC_OTH_IGST"));
			bean.setItcOthCgst(getNullableBigDecimal(rs, "ITC_OTH_CGST"));
			bean.setItcOthSgst(getNullableBigDecimal(rs, "ITC_OTH_SGST"));
			bean.setItcOthCess(getNullableBigDecimal(rs, "ITC_OTH_CESS"));

			bean.setItcIsdIgst(getNullableBigDecimal(rs, "ITC_ISD_IGST"));
			bean.setItcIsdCgst(getNullableBigDecimal(rs, "ITC_ISD_CGST"));
			bean.setItcIsdSgst(getNullableBigDecimal(rs, "ITC_ISD_SGST"));
			bean.setItcIsdCess(getNullableBigDecimal(rs, "ITC_ISD_CESS"));

			bean.setItcImpsIgst(getNullableBigDecimal(rs, "ITC_IMPS_IGST"));
			bean.setItcImpsCgst(getNullableBigDecimal(rs, "ITC_IMPS_CGST"));
			bean.setItcImpsSgst(getNullableBigDecimal(rs, "ITC_IMPS_SGST"));
			bean.setItcImpsCess(getNullableBigDecimal(rs, "ITC_IMPS_CESS"));

			// ITC Reversals & Net ITC
			bean.setItcRevRulIgst(getNullableBigDecimal(rs, "ITC_REV_RUL_IGST"));
			bean.setItcRevRulCgst(getNullableBigDecimal(rs, "ITC_REV_RUL_CGST"));
			bean.setItcRevRulSgst(getNullableBigDecimal(rs, "ITC_REV_RUL_SGST"));
			bean.setItcRevRulCess(getNullableBigDecimal(rs, "ITC_REV_RUL_CESS"));

			bean.setItcRevOthIgst(getNullableBigDecimal(rs, "ITC_REV_OTH_IGST"));
			bean.setItcRevOthCgst(getNullableBigDecimal(rs, "ITC_REV_OTH_CGST"));
			bean.setItcRevOthSgst(getNullableBigDecimal(rs, "ITC_REV_OTH_SGST"));
			bean.setItcRevOthCess(getNullableBigDecimal(rs, "ITC_REV_OTH_CESS"));

			bean.setNetItcIgst(getNullableBigDecimal(rs, "NET_ITC_IGST"));
			bean.setNetItcCgst(getNullableBigDecimal(rs, "NET_ITC_CGST"));
			bean.setNetItcSgst(getNullableBigDecimal(rs, "NET_ITC_SGST"));
			bean.setNetItcCess(getNullableBigDecimal(rs, "NET_ITC_CESS"));
			bean.setNetItcTotal(getNullableBigDecimal(rs, "NET_ITC_TOTAL"));

			// Ineligible & Aggregated ITC
			bean.setIneligibleItcRulIgst(getNullableBigDecimal(rs, "INELIGIBLE_ITC_RUL_IGST"));
			bean.setIneligibleItcRulCgst(getNullableBigDecimal(rs, "INELIGIBLE_ITC_RUL_CGST"));
			bean.setIneligibleItcRulSgst(getNullableBigDecimal(rs, "INELIGIBLE_ITC_RUL_SGST"));
			bean.setIneligibleItcRulCess(getNullableBigDecimal(rs, "INELIGIBLE_ITC_RUL_CESS"));

			bean.setIneligibleItcOthIgst(getNullableBigDecimal(rs, "INELIGIBLE_ITC_OTH_IGST"));
			bean.setIneligibleItcOthCgst(getNullableBigDecimal(rs, "INELIGIBLE_ITC_OTH_CGST"));
			bean.setIneligibleItcOthSgst(getNullableBigDecimal(rs, "INELIGIBLE_ITC_OTH_SGST"));
			bean.setIneligibleItcOthCess(getNullableBigDecimal(rs, "INELIGIBLE_ITC_OTH_CESS"));

			bean.setEligibleItc(getNullableBigDecimal(rs, "ELIGIBLE_ITC"));
			bean.setUtilizedItc(getNullableBigDecimal(rs, "UTILIZED_ITC"));
			bean.setReversedItc(getNullableBigDecimal(rs, "REVERSED_ITC"));
			bean.setIneligibleItc(getNullableBigDecimal(rs, "INELIGIBLE_ITC"));
			bean.setExcessItc(getNullableBigDecimal(rs, "EXCESS_ITC"));

			// Payments
			bean.setRcmPaymentIgst(getNullableBigDecimal(rs, "RCM_PAYMENT_IGST"));
			bean.setRcmPaymentCgst(getNullableBigDecimal(rs, "RCM_PAYMENT_CGST"));
			bean.setRcmPaymentSgst(getNullableBigDecimal(rs, "RCM_PAYMENT_SGST"));
			bean.setRcmPaymentCess(getNullableBigDecimal(rs, "RCM_PAYMENT_CESS"));
			bean.setRcmPaymentTotal(getNullableBigDecimal(rs, "RCM_PAYMENT_TOTAL"));

			bean.setCashIgstPaid(getNullableBigDecimal(rs, "CASH_IGST_PAID"));
			bean.setCashCgstPaid(getNullableBigDecimal(rs, "CASH_CGST_PAID"));
			bean.setCashSgstPaid(getNullableBigDecimal(rs, "CASH_SGST_PAID"));
			bean.setCashCessPaid(getNullableBigDecimal(rs, "CASH_CESS_PAID"));
			bean.setCashTaxPaid(getNullableBigDecimal(rs, "CASH_TAX_PAID"));

			bean.setItcPaymentIgst(getNullableBigDecimal(rs, "ITC_PAYMENT_IGST"));
			bean.setItcPaymentCgst(getNullableBigDecimal(rs, "ITC_PAYMENT_CGST"));
			bean.setItcPaymentSgst(getNullableBigDecimal(rs, "ITC_PAYMENT_SGST"));
			bean.setItcPaymentCess(getNullableBigDecimal(rs, "ITC_PAYMENT_CESS"));
			bean.setItcPaymentTotal(getNullableBigDecimal(rs, "ITC_PAYMENT_TOTAL"));

			// Interest, Late Fees & Flags
			bean.setInterestIgst(getNullableBigDecimal(rs, "INTEREST_IGST"));
			bean.setInterestCgst(getNullableBigDecimal(rs, "INTEREST_CGST"));
			bean.setInterestSgst(getNullableBigDecimal(rs, "INTEREST_SGST"));
			bean.setInterestCess(getNullableBigDecimal(rs, "INTEREST_CESS"));
			bean.setInterestPaid(getNullableBigDecimal(rs, "INTEREST_PAID"));

			bean.setLateFeeIgst(getNullableBigDecimal(rs, "LATE_FEE_IGST"));
			bean.setLateFeeCgst(getNullableBigDecimal(rs, "LATE_FEE_CGST"));
			bean.setLateFeeSgst(getNullableBigDecimal(rs, "LATE_FEE_SGST"));
			bean.setLateFeeCess(getNullableBigDecimal(rs, "LATE_FEE_CESS"));
			bean.setLateFeePaid(getNullableBigDecimal(rs, "LATE_FEE_PAID"));

			bean.setLateFeeApplicable(getNullableInteger(rs, "LATE_FEE_APPLICABLE"));
			bean.setCalculatedInterest(getNullableBigDecimal(rs, "CALCULATED_INTEREST"));
			bean.setInterestApplicable(getNullableInteger(rs, "INTEREST_APPLICABLE"));

			// E-Commerce
			bean.setEcommerceTurnover(getNullableBigDecimal(rs, "ECOMMERCE_TURNOVER"));
			bean.setEcommerceIgst(getNullableBigDecimal(rs, "ECOMMERCE_IGST"));
			bean.setEcommerceCgst(getNullableBigDecimal(rs, "ECOMMERCE_CGST"));
			bean.setEcommerceSgst(getNullableBigDecimal(rs, "ECOMMERCE_SGST"));
			bean.setEcommerceCess(getNullableBigDecimal(rs, "ECOMMERCE_CESS"));

			bean.setEcommerceRegisteredTurnover(getNullableBigDecimal(rs, "ECOMMERCE_REGISTERED_TURNOVER"));
			bean.setEcommerceRegisteredIgst(getNullableBigDecimal(rs, "ECOMMERCE_REGISTERED_IGST"));
			bean.setEcommerceRegisteredCgst(getNullableBigDecimal(rs, "ECOMMERCE_REGISTERED_CGST"));
			bean.setEcommerceRegisteredSgst(getNullableBigDecimal(rs, "ECOMMERCE_REGISTERED_SGST"));
			bean.setEcommerceRegisteredCess(getNullableBigDecimal(rs, "ECOMMERCE_REGISTERED_CESS"));

			// Derived Ratios
			bean.setNilSupplyRatio(getNullableBigDecimal(rs, "NIL_SUPPLY_RATIO"));
			bean.setItcUtilizationRatio(getNullableBigDecimal(rs, "ITC_UTILIZATION_RATIO"));
			bean.setItcToTaxRatio(getNullableBigDecimal(rs, "ITC_TO_TAX_RATIO"));
			bean.setCashPaymentRatio(getNullableBigDecimal(rs, "CASH_PAYMENT_RATIO"));
			bean.setItcPaymentRatio(getNullableBigDecimal(rs, "ITC_PAYMENT_RATIO"));
			bean.setRcmToTaxRatio(getNullableBigDecimal(rs, "RCM_TO_TAX_RATIO"));
			bean.setRcmItcRatio(getNullableBigDecimal(rs, "RCM_ITC_RATIO"));
			bean.setRcmCashRatio(getNullableBigDecimal(rs, "RCM_CASH_RATIO"));

			// Questionnaire
			bean.setQ1(getNullableString(rs, "Q1"));
			bean.setQ2(getNullableString(rs, "Q2"));
			bean.setQ3(getNullableString(rs, "Q3"));
			bean.setQ4(getNullableString(rs, "Q4"));
			bean.setQ5(getNullableString(rs, "Q5"));
			bean.setQ6(getNullableString(rs, "Q6"));
			bean.setQ7(getNullableString(rs, "Q7"));

			// AI Risk Scores
			bean.setXgbRiskScore(getNullableBigDecimal(rs, "XGB_RISK_SCORE"));
			bean.setDl4jAnomalyScore(getNullableBigDecimal(rs, "DL4J_ANOMALY_SCORE"));

			return bean;

		} catch (SQLException e) {
			log.error("Error mapping Return3B record from ResultSet", e);
			throw new RepositoryException("Failed to map return record");
		}
	};

	/* ============================================================
	   REFERENCE DATA QUERIES
	   ============================================================ */

	/**
	 * Fetches all available return periods with pagination safety.
	 */
	@Transactional(readOnly = true)
	@Retryable(
		retryFor = {RecoverableDataAccessException.class},
		maxAttempts = RETRY_ATTEMPTS,
		backoff = @Backoff(delay = RETRY_DELAY_MS)
	)
	@Override
	public List<ReturnPeriodOptionDto> getAllAvailableReturnPeriods() {
		try {
			// Note: Add LIMIT clause to the SQL query in QueryConstants
			return jdbcTemplate.query(FETCH_RETURN_PERIOD, (rs, rowNum) -> {
				try {
					String rawPeriod = rs.getString("ret_period");
					if (rawPeriod == null || rawPeriod.isBlank()) {
						log.warn("Null or blank ret_period encountered at row {}", rowNum);
						return null;
					}

					YearMonth yearMonth = YearMonth.parse(rawPeriod, INPUT_FORMATTER);
					String formattedLabel = yearMonth.format(OUTPUT_FORMATTER);

					return ReturnPeriodOptionDto.builder()
						.value(rawPeriod)
						.label(formattedLabel)
						.build();

				} catch (Exception e) {
					log.warn("Failed to parse return period at row {}: {}", rowNum, e.getMessage());
					return null;
				}
			}).stream().filter(p -> p != null).toList(); // Filter nulls

		} catch (RecoverableDataAccessException e) {
			log.warn("Recoverable error fetching return periods. Retrying...", e);
			throw e;

		} catch (DataAccessException e) {
			log.error("Failed to fetch return periods", e);
			throw new RepositoryException("Failed to fetch available return periods");
		}
	}

	/**
	 * Fetches risk summary for a specific return period with pagination.
	 */
	@Transactional(readOnly = true)
	@Retryable(
		retryFor = {RecoverableDataAccessException.class},
		maxAttempts = RETRY_ATTEMPTS,
		backoff = @Backoff(delay = RETRY_DELAY_MS)
	)
	@Override
	public List<GstRiskSummaryDto> getRiskSummary(String retPeriod) {
		if (retPeriod == null || retPeriod.isBlank()) {
			log.warn("getRiskSummary called with null or empty retPeriod");
			return Collections.emptyList();
		}

		String normalizedPeriod = retPeriod.trim();

		try {
			List<GstRiskSummaryDto> results = jdbcTemplate.query(
				FETCH_RISK_SUMMARY,
				(rs, rowNum) -> {
					try {
						return GstRiskSummaryDto.builder()
							.gstin(getNullableString(rs, "GSTIN"))
							.retPeriod(getNullableString(rs, "RET_PERIOD"))
							.taxableValue(getNullableBigDecimal(rs, "TAXABLE_VALUE"))
							.totalOutputTax(getNullableBigDecimal(rs, "TOTAL_OUTPUT_TAX"))
							.eligibleItc(getNullableBigDecimal(rs, "ELIGIBLE_ITC"))
							.utilizedItc(getNullableBigDecimal(rs, "UTILIZED_ITC"))
							.excessItc(getNullableBigDecimal(rs, "EXCESS_ITC"))
							.cashTaxPaid(getNullableBigDecimal(rs, "CASH_TAX_PAID"))
							.itcUtilizationRatio(getNullableDouble(rs, "ITC_UTILIZATION_RATIO"))
							.cashPaymentRatio(getNullableDouble(rs, "CASH_PAYMENT_RATIO"))
							.filingDelayDays(getNullableInteger(rs, "FILING_DELAY_DAYS"))
							.xgbRiskScore(getNullableDouble(rs, "XGB_RISK_SCORE"))
							.dl4jAnomalyScore(getNullableDouble(rs, "DL4J_ANOMALY_SCORE"))
							.build();
					} catch (SQLException e) {
						log.warn("Error mapping risk summary record at row {}", rowNum, e);
						return null;
					}
				},
				normalizedPeriod
			);

			// Filter out null mappings and log
			List<GstRiskSummaryDto> filteredResults = results.stream()
				.filter(r -> r != null)
				.toList();

			log.debug("Retrieved {} risk summary records for period: {}", filteredResults.size(), normalizedPeriod);
			return filteredResults;

		} catch (RecoverableDataAccessException e) {
			log.warn("Recoverable error fetching risk summary for period: {}. Retrying...", normalizedPeriod, e);
			throw e;

		} catch (DataAccessException e) {
			log.error("Failed to fetch risk summary for period: {}", normalizedPeriod, e);
			throw new RepositoryException("Failed to retrieve risk summary for period: " + normalizedPeriod);
		}
	}

	/* ============================================================
	   UTILITY METHODS
	   ============================================================ */

	/**
	 * Safely retrieves a nullable String from ResultSet.
	 */
	private String getNullableString(ResultSet rs, String columnName) throws SQLException {
		String value = rs.getString(columnName);
		return (value != null && !value.isBlank()) ? value : null;
	}

	/**
	 * Safely retrieves a nullable BigDecimal from ResultSet.
	 */
	private java.math.BigDecimal getNullableBigDecimal(ResultSet rs, String columnName) throws SQLException {
		java.math.BigDecimal value = rs.getBigDecimal(columnName);
		return rs.wasNull() ? null : value;
	}

	/**
	 * Safely retrieves a nullable Integer from ResultSet.
	 * Fixed: wasNull() check was missing in original code.
	 */
	private Integer getNullableInteger(ResultSet rs, String columnName) throws SQLException {
		int value = rs.getInt(columnName);
		return rs.wasNull() ? null : value;
	}

	/**
	 * Safely retrieves a nullable Double from ResultSet.
	 */
	private Double getNullableDouble(ResultSet rs, String columnName) throws SQLException {
		double value = rs.getDouble(columnName);
		return rs.wasNull() ? null : value;
	}

	/**
	 * Safely retrieves a nullable LocalDate from ResultSet.
	 */
	private LocalDate getLocalDate(ResultSet rs, String columnName) throws SQLException {
		java.sql.Date sqlDate = rs.getDate(columnName);
		return sqlDate != null ? sqlDate.toLocalDate() : null;
	}
}