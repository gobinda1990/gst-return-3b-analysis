package gov.com.ai.webapp.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import gov.com.ai.webapp.model.DashboardDTOs.ComplianceAlertDTO;
import gov.com.ai.webapp.model.DashboardDTOs.GstinAnalysisResponse;
import gov.com.ai.webapp.model.DashboardDTOs.Gstr3bMonthlyReturnDTO;
import gov.com.ai.webapp.model.DealerMaster;
import gov.com.ai.webapp.model.Return3BSummaryBean;
import gov.com.ai.webapp.repository.Return3bRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class Return3bAnalysisService {

	/*
	 * ============================================================ CONSTANTS
	 * ============================================================
	 */

	private static final int HISTORICAL_MONTHS_LOOKBACK = 12;
	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy");
	private static final Pattern GSTIN_PATTERN = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
	private static final BigDecimal ZERO = BigDecimal.ZERO;

	/* Risk Thresholds */
	private static final double RISK_HIGH = 0.75;
	private static final double RISK_MEDIUM = 0.40;

	/* Compliance Thresholds */
	private static final double ITC_CRITICAL_THRESHOLD = 0.98;
	private static final double CASH_HIGH_THRESHOLD = 0.80;
	private static final int FILING_DELAY_HIGH_DAYS = 30;

	private final Return3bRepository return3bRepository;
	private final XgbRiskScoringService xgbService;

	/*
	 * ============================================================ MAIN SERVICE
	 * METHOD ============================================================
	 */

	@Transactional(readOnly = true)
	public GstinAnalysisResponse getGstinAnalysis(String gstin) {

		String normalizedGstin = normalizeGstin(gstin);
		validateGstin(normalizedGstin);

		log.info("Generating GSTIN analysis. gstin={}, lookbackMonths={}", normalizedGstin, HISTORICAL_MONTHS_LOOKBACK);

		// 1. Fetch Taxpayer Profile
		Optional<DealerMaster> profile = return3bRepository.findByGstin(normalizedGstin);
		if (profile.isEmpty()) {
			log.warn("GSTIN profile not found in master record. gstin={}", normalizedGstin);
		}

		// 2. Fetch Historical Returns
		List<Return3BSummaryBean> historyBeans = return3bRepository.findHistory(normalizedGstin,
				HISTORICAL_MONTHS_LOOKBACK);

		if (historyBeans == null) {
			historyBeans = new ArrayList<>();
		}

		// 3. Metric Aggregators
		BigDecimal lifetimeTaxable = ZERO;
		BigDecimal lifetimeCash = ZERO;
		BigDecimal lifetimeItc = ZERO;
		BigDecimal lifetimeIgst = ZERO;
		BigDecimal lifetimeCgst = ZERO;
		BigDecimal lifetimeSgst = ZERO;
		BigDecimal lifetimeCess = ZERO;
		BigDecimal lifetimeOutputTax = ZERO;
		BigDecimal lifetimeRcm = ZERO;
		BigDecimal totalExcessItc = ZERO;

		List<Gstr3bMonthlyReturnDTO> historyList = new ArrayList<>();
		List<ComplianceAlertDTO> alerts = new ArrayList<>();
		Set<String> alertKeys = new HashSet<>();

		double highestRiskScore = 0.0;
		double latestRiskScore = 0.0;
		boolean latestRiskCaptured = false;

		// 4. Process Monthly Data
		for (Return3BSummaryBean bean : historyBeans) {
			if (bean == null) {
				continue;
			}

			if (bean.getGstin() == null) {
				bean.setGstin(normalizedGstin);
			}

			// Safe field extractions
			BigDecimal taxableValue = safe(bean.getTaxableValue());
			BigDecimal igst = safe(bean.getOutputIgst());
			BigDecimal cgst = safe(bean.getOutputCgst());
			BigDecimal sgst = safe(bean.getOutputSgst());
			BigDecimal cess = safe(bean.getOutputCess());
			BigDecimal outputTax = safe(bean.getTotalOutputTax());
			BigDecimal itcClaimed = safe(bean.getUtilizedItc());
			BigDecimal cashPaid = safe(bean.getCashTaxPaid());
			BigDecimal rcmTax = safe(bean.getRcmTotalTax());
			BigDecimal excessItc = safe(bean.getExcessItc());

			// Accumulate Lifetime Metrics
			lifetimeTaxable = lifetimeTaxable.add(taxableValue);
			lifetimeCash = lifetimeCash.add(cashPaid);
			lifetimeItc = lifetimeItc.add(itcClaimed);
			lifetimeIgst = lifetimeIgst.add(igst);
			lifetimeCgst = lifetimeCgst.add(cgst);
			lifetimeSgst = lifetimeSgst.add(sgst);
			lifetimeCess = lifetimeCess.add(cess);
			lifetimeOutputTax = lifetimeOutputTax.add(outputTax);
			lifetimeRcm = lifetimeRcm.add(rcmTax);
			totalExcessItc = totalExcessItc.add(excessItc);

			// Compute Financial Ratios
			double itcRatio = calculateRatio(bean.getItcUtilizationRatio(), outputTax, itcClaimed);
			double cashRatio = calculateRatio(bean.getCashPaymentRatio(), outputTax, cashPaid);

			int filingDelayDays = bean.getFilingDelayDays() != null ? Math.max(bean.getFilingDelayDays(), 0) : 0;
			String filingStatus = determineFilingStatus(bean, filingDelayDays);

			// Evaluate Risk Score
			double scoreVal = fetchRiskScore(bean);
			scoreVal = normalizeRiskScore(scoreVal);

			if (scoreVal > highestRiskScore) {
				highestRiskScore = scoreVal;
			}

			if (!latestRiskCaptured) {
				latestRiskScore = scoreVal;
				latestRiskCaptured = true;
			}

			// Build Monthly DTO
			Gstr3bMonthlyReturnDTO monthlyDto = Gstr3bMonthlyReturnDTO.builder().retPeriod(bean.getRetPeriod())
					.formattedPeriod(formatPeriodCode(bean.getRetPeriod())).taxableValue(taxableValue).igst(igst)
					.cgst(cgst).sgst(sgst).cess(cess).outputTax(outputTax).itcClaimed(itcClaimed).cashPaid(cashPaid)
					.rcmTax(rcmTax).itcRatio(roundRatio(itcRatio)).cashRatio(roundRatio(cashRatio))
					.filingDelayDays(filingDelayDays).filingStatus(filingStatus).build();

			historyList.add(monthlyDto);

			// Analyze Risk & Compliance Alerts
			evaluateReturnAlerts(bean, alerts, alertKeys, itcRatio, cashRatio);
		}

		// 5. Final Risk Categorization
		double currentRiskScore = latestRiskCaptured ? latestRiskScore : highestRiskScore;
		String overallRiskCategory = categorizeRiskScore(currentRiskScore);

		// 6. Taxpayer Meta Info
		String legalName = profile.map(DealerMaster::getLegalName).filter(this::hasText).orElse("N/A");
		String tradeName = profile.map(DealerMaster::getTradeName).filter(this::hasText).orElse("N/A");
		String jurisdiction = profile.map(DealerMaster::getStJuri).filter(this::hasText).orElse("N/A");
		String status = profile.map(DealerMaster::getAuthStatus).filter(this::hasText).orElse("ACTIVE");

		// 7. Construct Final Response Payload
		GstinAnalysisResponse response = GstinAnalysisResponse.builder().gstin(normalizedGstin).legalName(legalName)
				.tradeName(tradeName).jurisdiction(jurisdiction).taxpayerType("REGULAR").status(status)
				.lifetimeTaxableValue(scale2(lifetimeTaxable)).lifetimeCashPaid(scale2(lifetimeCash))
				.lifetimeItcUtilized(scale2(lifetimeItc)).currentRiskScore(roundRatio(currentRiskScore))
				.riskCategory(overallRiskCategory).last6MonthsHistory(historyList).activeAlerts(alerts).build();

		log.info("Analysis generated successfully. gstin={}, returnsProcessed={}, riskCategory={}", normalizedGstin,
				historyList.size(), overallRiskCategory);

		return response;
	}

	/*
	 * ============================================================ HELPER METHODS
	 * ============================================================
	 */

	private double fetchRiskScore(Return3BSummaryBean bean) {
		if (xgbService != null) {
			try {
				BigDecimal predictedScore = xgbService.predictRiskScore(bean);
				if (predictedScore != null) {
					return predictedScore.doubleValue();
				}
			} catch (Exception ex) {
				log.warn("XGBoost scoring failed for period={}. Falling back to DB metric.", bean.getRetPeriod(), ex);
			}
		}
		BigDecimal databaseScore = bean.getXgbRiskScore();
		return databaseScore != null ? databaseScore.doubleValue() : 0.0;
	}

	private double normalizeRiskScore(double score) {
		if (Double.isNaN(score) || Double.isInfinite(score) || score < 0) {
			return 0.0;
		}
		if (score > 1.0 && score <= 100.0) {
			score = score / 100.0;
		}
		return Math.min(Math.max(score, 0.0), 1.0);
	}

	private void evaluateReturnAlerts(Return3BSummaryBean bean, List<ComplianceAlertDTO> alerts, Set<String> alertKeys,
			double itcRatio, double cashRatio) {
		String period = bean.getRetPeriod();

		if (itcRatio >= ITC_CRITICAL_THRESHOLD) {
			addAlertIfAbsent(alerts, alertKeys, bean, "ITC-" + period,
					"High Risk: ITC utilization exceeds 98% of total tax liability.", safe(bean.getExcessItc()),
					"CRITICAL");
		}

		int filingDelayDays = bean.getFilingDelayDays() != null ? Math.max(bean.getFilingDelayDays(), 0) : 0;
		if (filingDelayDays > FILING_DELAY_HIGH_DAYS) {
			addAlertIfAbsent(alerts, alertKeys, bean, "DELAY-" + period,
					"Filing Anomaly: Return filed with significant delay (" + filingDelayDays + " days).", ZERO,
					"HIGH");
		}

		if (cashRatio >= CASH_HIGH_THRESHOLD) {
			addAlertIfAbsent(alerts, alertKeys, bean, "CASH-" + period, "High cash tax payment dependency detected.",
					ZERO, "MEDIUM");
		}

		BigDecimal excessItc = safe(bean.getExcessItc());
		if (excessItc.compareTo(ZERO) > 0) {
			addAlertIfAbsent(alerts, alertKeys, bean, "EXCESS-ITC-" + period, "Excess ITC claimed in the period.",
					excessItc, "HIGH");
		}
	}

	private void addAlertIfAbsent(List<ComplianceAlertDTO> alerts, Set<String> alertKeys, Return3BSummaryBean bean,
			String alertKey, String message, BigDecimal excessItc, String severity) {
		if (!alertKeys.add(alertKey)) {
			return;
		}

		alerts.add(ComplianceAlertDTO.builder().id(UUID.randomUUID().toString()).gstin(bean.getGstin())
				.retPeriod(bean.getRetPeriod()).message(message).excessItc(safe(excessItc)).severity(severity)
				.formattedDate(LocalDate.now().format(DATE_FORMATTER)).build());
	}

	private String determineFilingStatus(Return3BSummaryBean bean, int filingDelayDays) {
		if (bean.getFilingDate() == null) {
			return "PENDING";
		}
		return filingDelayDays > 0 ? "DELAYED" : "FILED";
	}

	private String categorizeRiskScore(double score) {
		if (score >= RISK_HIGH)
			return "HIGH";
		if (score >= RISK_MEDIUM)
			return "MEDIUM";
		return "LOW";
	}

	private String formatPeriodCode(String retPeriod) {
		if (retPeriod == null || retPeriod.length() != 6) {
			return retPeriod;
		}
		try {
			int month = Integer.parseInt(retPeriod.substring(0, 2));
			String year = retPeriod.substring(2, 6);
			if (month < 1 || month > 12)
				return retPeriod;
			return String.format("%02d/%s", month, year);
		} catch (NumberFormatException e) {
			return retPeriod;
		}
	}

	private BigDecimal safe(BigDecimal value) {
		return value == null ? ZERO : value;
	}

	private double calculateRatio(BigDecimal dbRatio, BigDecimal denominator, BigDecimal numerator) {
		if (dbRatio != null) {
			double val = dbRatio.setScale(4, RoundingMode.HALF_UP).doubleValue();
			if (val > 1.0)
				val = val / 100.0;
			return clampRatio(val);
		}
		BigDecimal safeDenom = safe(denominator);
		BigDecimal safeNum = safe(numerator);

		if (safeDenom.compareTo(ZERO) > 0) {
			return clampRatio(safeNum.divide(safeDenom, 4, RoundingMode.HALF_UP).doubleValue());
		}
		return 0.0;
	}

	private double clampRatio(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value))
			return 0.0;
		return Math.min(Math.max(value, 0.0), 1.0);
	}

	private double roundRatio(double value) {
		return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
	}

	private BigDecimal scale2(BigDecimal value) {
		return safe(value).setScale(2, RoundingMode.HALF_UP);
	}

	private String normalizeGstin(String gstin) {
		return gstin == null ? "" : gstin.trim().toUpperCase();
	}

	private void validateGstin(String gstin) {
		if (!hasText(gstin)) {
			throw new IllegalArgumentException("GSTIN input parameter cannot be empty.");
		}
		if (!GSTIN_PATTERN.matcher(gstin).matches()) {
			throw new IllegalArgumentException("Provided GSTIN string failed validation pattern: " + gstin);
		}
	}

	private boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}
}