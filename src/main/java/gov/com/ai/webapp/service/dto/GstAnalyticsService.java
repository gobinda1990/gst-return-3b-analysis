package gov.com.ai.webapp.service.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gov.com.ai.webapp.exception.GstAnalyticsException;
import gov.com.ai.webapp.exception.GstDataNotFoundException;
import gov.com.ai.webapp.model.dto.GstAnalyticsResponse;
import gov.com.ai.webapp.model.dto.GstCompliance;
import gov.com.ai.webapp.model.dto.GstKpi;
import gov.com.ai.webapp.model.dto.GstMlAnalysis;
import gov.com.ai.webapp.model.dto.GstMonthlyTrend;
import gov.com.ai.webapp.model.dto.GstRatioAnalysis;
import gov.com.ai.webapp.model.dto.GstTaxBreakup;
import gov.com.ai.webapp.repository.dto.GstAnalyticsRepository;
import gov.com.ai.webapp.repository.dto.GstAnalyticsRow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class GstAnalyticsService {

	private static final BigDecimal ZERO = BigDecimal.ZERO;
	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
	private static final BigDecimal RISK_CRITICAL = BigDecimal.valueOf(75);
	private static final BigDecimal RISK_HIGH = BigDecimal.valueOf(50);
	private static final BigDecimal RISK_MEDIUM = BigDecimal.valueOf(25);
	private static final int MONTHS_TO_FETCH = 24;

	private final GstAnalyticsRepository repository;

	/**
	 * Fetch complete GST analytics. Cache key is based on normalized GSTIN.
	 */
	@Transactional(readOnly = true)
	@Cacheable(cacheNames = "gstAnalytics", key = "T(gov.com.ai.webapp.service.dto.GstAnalyticsService)"
			+ ".normalizeGstin(#gstin)", unless = "#result == null")
	public GstAnalyticsResponse getAnalytics(String gstin) {

		final String normalizedGstin = validateAndNormalizeGstin(gstin);
		long start = System.currentTimeMillis();

		try {
			// Unwrap Optional using orElseThrow
			GstAnalyticsRow row = repository.fetchAnalytics(normalizedGstin)
					.orElseThrow(() -> new GstDataNotFoundException("No GST 3B analytics data found for " + normalizedGstin));

			List<GstMonthlyTrend> trend = repository.fetchMonthlyTrend(normalizedGstin, MONTHS_TO_FETCH);
			if (trend == null) {
				trend = List.of();
			}

			GstAnalyticsResponse response = buildResponse(row, trend);

			log.debug("GST analytics fetched successfully GSTIN={} durationMs={}", 
					normalizedGstin, System.currentTimeMillis() - start);

			return response;

		} catch (GstDataNotFoundException ex) {
			throw ex;

		} catch (EmptyResultDataAccessException ex) {
			log.debug("No GST analytics data found GSTIN={}", normalizedGstin);
			throw new GstDataNotFoundException("No GST 3B data found for " + normalizedGstin);

		} catch (DataAccessException ex) {
			log.error("Database error while fetching GST analytics GSTIN={}", normalizedGstin, ex);
			throw new GstAnalyticsException("Unable to fetch GST analytics");

		} catch (GstAnalyticsException ex) {
			throw ex;

		} catch (Exception ex) {
			log.error("Unexpected error while generating GST analytics GSTIN={}", normalizedGstin, ex);
			throw new GstAnalyticsException("Unexpected error while generating GST analytics");
		}
	}

	/**
	 * Builds complete analytics response.
	 */
	private GstAnalyticsResponse buildResponse(GstAnalyticsRow r, List<GstMonthlyTrend> trend) {

		BigDecimal outputTax = safe(r.outputTax());
		BigDecimal utilizedItc = safe(r.utilizedItc());
		BigDecimal taxPayable = outputTax.subtract(utilizedItc);

		if (taxPayable.signum() < 0) {
			taxPayable = ZERO;
		}

		GstKpi kpi = new GstKpi(safe(r.taxableTurnover()), outputTax, safe(r.eligibleItc()), utilizedItc,
				safe(r.reversedItc()), safe(r.ineligibleItc()), safe(r.excessItc()), taxPayable, safe(r.cashTaxPaid()),
				safe(r.itcPaymentTotal()), safe(r.interestPaid()), safe(r.lateFeePaid()));

		GstTaxBreakup tax = new GstTaxBreakup(safe(r.outputIgst()), safe(r.outputCgst()), safe(r.outputSgst()),
				safe(r.outputCess()), safe(r.rcmTotalTax()), safe(r.zeroRatedValue()), safe(r.nilExemptValue()),
				safe(r.nonGstValue()), safe(r.rcmTaxableValue()));

		GstCompliance compliance = buildCompliance(r);
		GstRatioAnalysis ratios = buildRatios(r);

		BigDecimal riskScore = safeNullable(r.xgbRiskScore());
		BigDecimal anomalyScore = safeNullable(r.anomalyScore());

		GstMlAnalysis ml = new GstMlAnalysis(riskScore, anomalyScore, riskLevel(riskScore));

		return new GstAnalyticsResponse(
				r.gstin(), 
				r.stateCode(), 
				safeInt(r.totalReturns()),
				toLocalDate(r.firstFilingDate()),
				toLocalDate(r.latestFilingDate()),
				kpi, 
				tax,
				null, // Detailed ITC breakup
				null, // Payment breakup
				compliance, 
				ratios, 
				ml, 
				trend
		);
	}

	private GstRatioAnalysis buildRatios(GstAnalyticsRow r) {

		BigDecimal tax = safe(r.outputTax());
		BigDecimal itc = safe(r.eligibleItc());
		BigDecimal utilized = safe(r.utilizedItc());
		BigDecimal cashPaid = safe(r.cashTaxPaid());
		BigDecimal itcPayment = safe(r.itcPaymentTotal());
		BigDecimal rcm = safe(r.rcmTotalTax());

		return new GstRatioAnalysis(
				percentage(safe(r.nilExemptValue()), safe(r.taxableTurnover())),
				percentage(utilized, itc),
				percentage(itc, tax),
				percentage(cashPaid, tax),
				percentage(itcPayment, tax),
				percentage(rcm, tax),
				percentage(rcm, itc),
				percentage(rcm, cashPaid)
		);
	}

	private BigDecimal percentage(BigDecimal numerator, BigDecimal denominator) {
		if (numerator == null || denominator == null || denominator.signum() == 0) {
			return ZERO;
		}
		return numerator.multiply(HUNDRED).divide(denominator, 2, RoundingMode.HALF_UP);
	}

	private GstCompliance buildCompliance(GstAnalyticsRow r) {

		int total = safeInt(r.totalReturns());
		int ontime = safeInt(r.ontimeReturns());
		int delayed = safeInt(r.delayedReturns());
		double onTimePercentage = total <= 0 ? 0.0 : (ontime * 100.0) / total;
		BigDecimal averageDelay = safe(r.averageDelay());
		int maxDelay = safeInt(r.maxDelay());

		return new GstCompliance(
				total, 
				delayed, 
				ontime,
				0, // Currently not available
				maxDelay,
				round(onTimePercentage),
				averageDelay.doubleValue(),
				maxDelay > 0,
				false
		);
	}

	private double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}

	private String riskLevel(BigDecimal score) {
		if (score == null) {
			return "UNKNOWN";
		}
		if (score.compareTo(RISK_CRITICAL) >= 0) {
			return "CRITICAL";
		}
		if (score.compareTo(RISK_HIGH) >= 0) {
			return "HIGH";
		}
		if (score.compareTo(RISK_MEDIUM) >= 0) {
			return "MEDIUM";
		}
		return "LOW";
	}

	public static String normalizeGstin(String gstin) {
		if (gstin == null) {
			return null;
		}
		return gstin.trim().toUpperCase().replaceAll("[\\s-]", "");
	}

	private String validateAndNormalizeGstin(String gstin) {
		if (gstin == null || gstin.isBlank()) {
			throw new IllegalArgumentException("GSTIN is required");
		}

		String normalized = normalizeGstin(gstin);
		String gstinRegex = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$";

		if (!normalized.matches(gstinRegex)) {
			throw new IllegalArgumentException("Invalid GSTIN format");
		}

		return normalized;
	}

	private LocalDate toLocalDate(Date date) {
		if (date == null) {
			return null;
		}
		if (date instanceof java.sql.Date sqlDate) {
			return sqlDate.toLocalDate();
		}
		return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
	}

	private BigDecimal safe(BigDecimal value) {
		return value == null ? ZERO : value;
	}

	private BigDecimal safeNullable(BigDecimal value) {
		return value;
	}

	private int safeInt(Integer value) {
		return value == null ? 0 : value;
	}

	@CacheEvict(cacheNames = "gstAnalytics", key = "T(gov.com.ai.webapp.service.dto.GstAnalyticsService)"
			+ ".normalizeGstin(#gstin)")
	public void evictAnalyticsCache(String gstin) {
		log.debug("GST analytics cache evicted GSTIN={}", normalizeGstin(gstin));
	}

	@CacheEvict(cacheNames = "gstAnalytics", allEntries = true)
	public void clearAnalyticsCache() {
		log.info("GST analytics cache cleared");
	}
}