package gov.com.ai.webapp.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import gov.com.ai.webapp.model.DashboardDTOs;
import gov.com.ai.webapp.model.Return3BSummaryBean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class GstReturn3bRiskAssessmentService {

	private final RiskFindingToAlertConverter alertConverter;

	private static final BigDecimal ITC_TO_TAX_DISPROPORTIONATE_THRESHOLD = new BigDecimal("5.0");
	private static final BigDecimal NIL_SUPPLY_REVERSAL_THRESHOLD = new BigDecimal("0.70");
	private static final BigDecimal ITC_UTILIZATION_NEAR_TOTAL = new BigDecimal("0.99");
	private static final BigDecimal CASH_PAYMENT_NEAR_ZERO = new BigDecimal("0.01");
	private static final BigDecimal RCM_ITC_RATIO_IMBALANCE_THRESHOLD = new BigDecimal("1.5");
	private static final BigDecimal AI_RISK_SCORE_THRESHOLD = new BigDecimal("0.70");
	private static final String ENGINE_VERSION = "risk-svc-1.0";

	/**
	 * Main entry point: Assess a GSTR-3B summary and return alerts.
	 * 
	 * No persistence - returns all findings converted to ComplianceAlertDTO for
	 * immediate use in dashboard or caller responsibility for persistence.
	 */
	public RiskAssessmentResult assess(Return3BSummaryBean summary) {
		if (summary == null) {
			throw new IllegalArgumentException("Summary bean must not be null");
		}

		log.info("Starting risk assessment. gstin={}, retPeriod={}", summary.getGstin(), summary.getRetPeriod());

		// Detect all findings per GST Act rules
		List<RiskFinding> findings = detectFindings(summary);

		// Calculate composite risk score (0-100)
		int compositeScore = calculateCompositeScore(findings);

		// Determine overall risk level
		String riskLevel = determineRiskLevel(compositeScore);

		// Count CRITICAL findings
		boolean hardViolationDetected = findings.stream().anyMatch(f -> "CRITICAL".equals(f.severity));

		// Count ITC/RCM findings
		boolean itcAnomalyDetected = findings.stream()
				.anyMatch(f -> "ITC".equals(f.category) || "RCM".equals(f.category));

		// Convert findings to alerts (no persistence)
		List<DashboardDTOs.ComplianceAlertDTO> alerts = new ArrayList<>();

		for (RiskFinding finding : findings) {
			DashboardDTOs.ComplianceAlertDTO alert = alertConverter.convertToAlert(summary, finding, compositeScore);
			alerts.add(alert);
		}

		log.info("Assessment complete. gstin={}, retPeriod={}, riskLevel={}, score={}, " + "findings={}, alerts={}",
				summary.getGstin(), summary.getRetPeriod(), riskLevel, compositeScore, findings.size(), alerts.size());

		// Return assessment result containing alerts and metadata
		return RiskAssessmentResult.builder().gstin(summary.getGstin()).retPeriod(summary.getRetPeriod())
				.riskLevel(riskLevel).compositeScore(compositeScore).totalFindings(findings.size())
				.criticalFindings((int) findings.stream().filter(f -> "CRITICAL".equals(f.severity)).count())
				.highFindings((int) findings.stream().filter(f -> "HIGH".equals(f.severity)).count())
				.mediumFindings((int) findings.stream().filter(f -> "MEDIUM".equals(f.severity)).count())
				.itcAnomalyDetected(itcAnomalyDetected).hardViolationDetected(hardViolationDetected)
				.engineVersion(ENGINE_VERSION).assessmentTimestamp(LocalDateTime.now()).alerts(alerts).build();
	}

	/**
	 * Detect all findings per GST Act rules
	 */
	private List<RiskFinding> detectFindings(Return3BSummaryBean summary) {
		List<RiskFinding> findings = new ArrayList<>();

		// CRITICAL FINDINGS
		findings.addAll(detectItcExcessUtilized(summary));
		findings.addAll(detectRcmLiabilityNotCashDischarged(summary));

		// HIGH FINDINGS
		findings.addAll(detectItcNearTotalWithMinimalCash(summary));
		findings.addAll(detectRcmItcClaimedWithoutCashDischarge(summary));

		// MEDIUM FINDINGS
		findings.addAll(detectItcDisproprtionateToCashOutput(summary));
		findings.addAll(detectItcNoReversalDespiteHighExempt(summary));
		findings.addAll(detectInterestLikelyUnderpaid(summary));
		findings.addAll(detectRcmItcPaymentImbalance(summary));
		findings.addAll(detectLateLikelyUnderpaid(summary));
		findings.addAll(detectAiXgbHighScore(summary));
		findings.addAll(detectAiDl4jHighAnomaly(summary));

		return findings;
	}

	// ===================================================================
	// CRITICAL FINDINGS
	// ===================================================================

	private List<RiskFinding> detectItcExcessUtilized(Return3BSummaryBean summary) {
		List<RiskFinding> findings = new ArrayList<>();

		BigDecimal eligibleItc = safe(summary.getEligibleItc());
		BigDecimal reversedItc = safe(summary.getReversedItc());
		BigDecimal utilizedItc = safe(summary.getUtilizedItc());

		BigDecimal netAvailableItc = eligibleItc.subtract(reversedItc);
		if (netAvailableItc.compareTo(BigDecimal.ZERO) < 0) {
			netAvailableItc = BigDecimal.ZERO;
		}

		if (utilizedItc.compareTo(netAvailableItc) > 0) {
			BigDecimal excessAmount = utilizedItc.subtract(netAvailableItc);
			findings.add(new RiskFinding("ITC_EXCESS_UTILIZED", "ITC", "CRITICAL", String.format(
					"Utilized ITC (Rs.%,.2f) exceeds net available ITC (Rs.%,.2f = Eligible Rs.%,.2f - Reversed Rs.%,.2f) by Rs.%,.2f. "
							+ "Violation of Sec 16/17 read with Sec 41/42 CGST Act.",
					utilizedItc, netAvailableItc, eligibleItc, reversedItc, excessAmount), excessAmount,
					"Sec 16 (Eligibility), Sec 17 (Apportionment), Sec 41/42 (Availment & Reversal)"));
		}
		return findings;
	}

	private List<RiskFinding> detectRcmLiabilityNotCashDischarged(Return3BSummaryBean summary) {
		List<RiskFinding> findings = new ArrayList<>();

		BigDecimal rcmTotalTax = safe(summary.getRcmTotalTax());
		BigDecimal rcmPaymentTotal = safe(summary.getRcmPaymentTotal());

		if (rcmTotalTax.compareTo(BigDecimal.ZERO) > 0 && rcmPaymentTotal.compareTo(BigDecimal.ZERO) == 0) {

			findings.add(new RiskFinding("RCM_LIABILITY_NOT_CASH_DISCHARGED", "RCM", "CRITICAL",
					String.format("RCM tax liability reported (Rs.%,.2f) but no RCM cash payment made in the period. "
							+ "Violation of Sec 9(3)/9(4) CGST Act (reverse charge) read with Sec 49(4) - "
							+ "RCM liability must be discharged in cash, not via ITC.", rcmTotalTax),
					rcmTotalTax, "Sec 9(3)/9(4) CGST Act (Reverse Charge), Sec 49(4) (Cash Payment Requirement)"));
		}
		return findings;
	}

	// ===================================================================
	// HIGH FINDINGS
	// ===================================================================

	private List<RiskFinding> detectItcNearTotalWithMinimalCash(Return3BSummaryBean summary) {
		List<RiskFinding> findings = new ArrayList<>();

		BigDecimal itcUtilizationRatio = safe(summary.getItcUtilizationRatio());
		BigDecimal cashPaymentRatio = safe(summary.getCashPaymentRatio());

		if (itcUtilizationRatio.compareTo(ITC_UTILIZATION_NEAR_TOTAL) >= 0
				&& cashPaymentRatio.compareTo(CASH_PAYMENT_NEAR_ZERO) < 0) {

			findings.add(new RiskFinding("ITC_NEAR_TOTAL_WITH_MINIMAL_CASH", "ITC", "HIGH",
					String.format(
							"ITC utilization ratio %.2f%% (>= 99%%) combined with cash payment ratio %.2f%% (< 1%%). "
									+ "Pattern indicator: Rule 86B CGST Rules mandates minimum 1%% cash payment. "
									+ "This may indicate credit-driven discharge without cash outflow.",
							itcUtilizationRatio.multiply(new BigDecimal("100")),
							cashPaymentRatio.multiply(new BigDecimal("100"))),
					null, "Rule 86B CGST Rules (Mandatory Minimum 1%% Cash Payment)"));
		}
		return findings;
	}

	private List<RiskFinding> detectRcmItcClaimedWithoutCashDischarge(Return3BSummaryBean summary) {
		List<RiskFinding> findings = new ArrayList<>();

		BigDecimal isrcItc = safeSum(summary.getItcIsrcIgst(), summary.getItcIsrcCgst(), summary.getItcIsrcSgst(),
				summary.getItcIsrcCess());
		BigDecimal rcmCashRatio = safe(summary.getRcmCashRatio());

		if (isrcItc.compareTo(BigDecimal.ZERO) > 0 && rcmCashRatio.compareTo(BigDecimal.ZERO) == 0) {

			findings.add(new RiskFinding("RCM_ITC_CLAIMED_WITHOUT_CASH_DISCHARGE", "RCM", "HIGH", String.format(
					"ISRC-type ITC (Rs.%,.2f) claimed on reverse-charge inward supplies with zero RCM cash payment ratio. "
							+ "Violation of Sec 16(2)(c)/16(4) read with Sec 9(3) CGST Act - "
							+ "ITC on RCM supplies is available only after the RCM tax is actually paid.",
					isrcItc), isrcItc, "Sec 16(2)(c)/16(4) (ITC Eligibility on RCM), Sec 9(3) (Reverse Charge)"));
		}
		return findings;
	}

	// ===================================================================
	// MEDIUM FINDINGS
	// ===================================================================

	private List<RiskFinding> detectItcDisproprtionateToCashOutput(Return3BSummaryBean summary) {
		List<RiskFinding> findings = new ArrayList<>();

		BigDecimal eligibleItc = safe(summary.getEligibleItc());
		BigDecimal totalOutputTax = safe(summary.getTotalOutputTax());

		if (totalOutputTax.compareTo(BigDecimal.ZERO) > 0
				&& eligibleItc.compareTo(totalOutputTax.multiply(ITC_TO_TAX_DISPROPORTIONATE_THRESHOLD)) >= 0) {

			BigDecimal ratio = safeDivide(eligibleItc, totalOutputTax);
			findings.add(new RiskFinding("ITC_DISPROPORTIONATE_TO_OUTPUT_TAX", "ITC", "MEDIUM",
					String.format(
							"Eligible ITC (Rs.%,.2f) is %.2fx of output tax liability (Rs.%,.2f). "
									+ "General scrutiny indicator - not tied to a single section; "
									+ "check for inverted duty structure or ITC accumulation.",
							eligibleItc, ratio, totalOutputTax),
					ratio, "Sec 16 (ITC Eligibility) - General Scrutiny Pattern"));
		}
		return findings;
	}

	private List<RiskFinding> detectItcNoReversalDespiteHighExempt(Return3BSummaryBean summary) {
		List<RiskFinding> findings = new ArrayList<>();

		BigDecimal nilSupplyRatio = safe(summary.getNilSupplyRatio());
		BigDecimal reversedItc = safe(summary.getReversedItc());

		if (nilSupplyRatio.compareTo(NIL_SUPPLY_REVERSAL_THRESHOLD) >= 0
				&& reversedItc.compareTo(BigDecimal.ZERO) == 0) {

			findings.add(new RiskFinding("ITC_NO_REVERSAL_DESPITE_HIGH_EXEMPT_SUPPLY", "ITC", "MEDIUM", String.format(
					"Nil-rated/exempt supply %.2f%% of turnover (>= 70%%) with zero ITC reversal reported. "
							+ "Violation of Rule 42/43 CGST Rules - proportionate reversal of ITC attributable to exempt supplies is mandatory.",
					nilSupplyRatio.multiply(new BigDecimal("100"))), nilSupplyRatio,
					"Rule 42/43 CGST Rules (Proportionate ITC Reversal on Exempt Supplies)"));
		}
		return findings;
	}

	private List<RiskFinding> detectInterestLikelyUnderpaid(Return3BSummaryBean summary) {
		List<RiskFinding> findings = new ArrayList<>();

		Integer filingDelayDays = summary.getFilingDelayDays();
		BigDecimal calculatedInterest = safe(summary.getCalculatedInterest());
		BigDecimal reportedInterest = safe(summary.getInterestPaid());

		if (filingDelayDays != null && filingDelayDays >= 30 && calculatedInterest.compareTo(reportedInterest) > 0) {

			BigDecimal shortfall = calculatedInterest.subtract(reportedInterest);
			findings.add(new RiskFinding("INTEREST_LIKELY_UNDERPAID", "COMPLIANCE", "MEDIUM", String.format(
					"Filing delay %d days detected. Calculated Sec 50(1) interest (18%% p.a.) = Rs.%,.2f; "
							+ "Interest reported as paid = Rs.%,.2f. Shortfall = Rs.%,.2f liable to reversal + interest.",
					filingDelayDays, calculatedInterest, reportedInterest, shortfall), shortfall,
					"Sec 50(1) CGST Act (Interest on Delayed Payment of Tax)"));
		}
		return findings;
	}

	private List<RiskFinding> detectRcmItcPaymentImbalance(Return3BSummaryBean summary) {
		List<RiskFinding> findings = new ArrayList<>();

		BigDecimal rcmItcRatio = safe(summary.getRcmItcRatio());

		if (rcmItcRatio.compareTo(RCM_ITC_RATIO_IMBALANCE_THRESHOLD) > 0) {
			findings.add(new RiskFinding("RCM_ITC_PAYMENT_RATIO_IMBALANCE", "RCM", "MEDIUM",
					String.format(
							"RCM ITC ratio %.2f%% (> 150%% threshold). "
									+ "ITC claimed on RCM supplies is disproportionate to RCM liability.",
							rcmItcRatio.multiply(new BigDecimal("100"))),
					rcmItcRatio, "Sec 16(4) (ITC on RCM Supplies)"));
		}
		return findings;
	}

	private List<RiskFinding> detectLateLikelyUnderpaid(Return3BSummaryBean summary) {
		List<RiskFinding> findings = new ArrayList<>();

		Integer filingDelayDays = summary.getFilingDelayDays();
		BigDecimal calculatedLateFee = safe(summary.getCalculatedLateFee());
		BigDecimal reportedLateFee = safe(summary.getLateFeePaid());

		if (filingDelayDays != null && filingDelayDays > 0 && calculatedLateFee.compareTo(reportedLateFee) > 0) {

			BigDecimal shortfall = calculatedLateFee.subtract(reportedLateFee);
			findings.add(
					new RiskFinding("LATE_FEE_LIKELY_UNDERPAID", "COMPLIANCE", "MEDIUM",
							String.format("Filing delay %d days detected. Calculated Sec 47 late fee = Rs.%,.2f; "
									+ "Late fee reported as paid = Rs.%,.2f. Shortfall = Rs.%,.2f liable to demand.",
									filingDelayDays, calculatedLateFee, reportedLateFee, shortfall),
							shortfall, "Sec 47 CGST Act (Late Fee for Delayed Filing)"));
		}
		return findings;
	}

	private List<RiskFinding> detectAiXgbHighScore(Return3BSummaryBean summary) {
		List<RiskFinding> findings = new ArrayList<>();

		BigDecimal xgbScore = safe(summary.getXgbRiskScore());

		if (xgbScore.compareTo(AI_RISK_SCORE_THRESHOLD) >= 0) {
			findings.add(new RiskFinding("AI_XGB_HIGH_RISK_SCORE", "AI_MODEL", "MEDIUM",
					String.format(
							"XGBoost risk model score = %.4f (>= threshold %.2f). "
									+ "Corroborating signal for ITC anomaly and compliance risk.",
							xgbScore, AI_RISK_SCORE_THRESHOLD),
					xgbScore, "Model-derived signal only; not a statutory provision but supportive intelligence."));
		}
		return findings;
	}

	private List<RiskFinding> detectAiDl4jHighAnomaly(Return3BSummaryBean summary) {
		List<RiskFinding> findings = new ArrayList<>();

		BigDecimal dl4jScore = safe(summary.getDl4jAnomalyScore());

		if (dl4jScore.compareTo(AI_RISK_SCORE_THRESHOLD) >= 0) {
			findings.add(new RiskFinding("AI_DL4J_HIGH_ANOMALY_SCORE", "AI_MODEL", "MEDIUM",
					String.format(
							"DL4J anomaly model score = %.4f (>= threshold %.2f). "
									+ "Statistical anomaly detected in return pattern.",
							dl4jScore, AI_RISK_SCORE_THRESHOLD),
					dl4jScore, "Model-derived signal only; not a statutory provision but supportive intelligence."));
		}
		return findings;
	}

	// ===================================================================
	// RISK SCORING & VERDICT
	// ===================================================================

	private int calculateCompositeScore(List<RiskFinding> findings) {
		int score = 0;
		for (RiskFinding finding : findings) {
			switch (finding.severity) {
			case "CRITICAL" -> score += 30;
			case "HIGH" -> score += 15;
			case "MEDIUM" -> score += 5;
			case "LOW" -> score += 1;
			}
		}
		return Math.min(score, 100);
	}

	private String determineRiskLevel(int compositeScore) {
		if (compositeScore >= 50)
			return "HIGH";
		if (compositeScore >= 20)
			return "MEDIUM";
		return "LOW";
	}

	// ===================================================================
	// HELPERS
	// ===================================================================

	private BigDecimal safe(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

//    private BigDecimal safe(Integer value) {
//        return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value);
//    }

	private BigDecimal safeSum(BigDecimal... values) {
		BigDecimal total = BigDecimal.ZERO;
		if (values == null)
			return total;
		for (BigDecimal v : values) {
			if (v != null)
				total = total.add(v);
		}
		return total;
	}

	private BigDecimal safeDivide(BigDecimal dividend, BigDecimal divisor) {
		BigDecimal d = safe(divisor);
		if (d.compareTo(BigDecimal.ZERO) == 0)
			return BigDecimal.ZERO;
		return safe(dividend).divide(d, 4, RoundingMode.HALF_UP);
	}

	/**
	 * Internal DTO for findings during processing
	 */
	static class RiskFinding {
		String code;
		String category;
		String severity;
		String message;
		BigDecimal evidenceValue;
		String legalBasis;

		RiskFinding(String code, String category, String severity, String message, BigDecimal evidenceValue,
				String legalBasis) {
			this.code = code;
			this.category = category;
			this.severity = severity;
			this.message = message;
			this.evidenceValue = evidenceValue;
			this.legalBasis = legalBasis;
		}
	}

	/**
	 * Assessment result containing alerts and metadata
	 */
	@lombok.Data
	@lombok.Builder
	@lombok.NoArgsConstructor
	@lombok.AllArgsConstructor
	public static class RiskAssessmentResult {
		private String gstin;
		private String retPeriod;
		private String riskLevel; // HIGH, MEDIUM, LOW
		private int compositeScore; // 0-100
		private int totalFindings;
		private int criticalFindings;
		private int highFindings;
		private int mediumFindings;
		private boolean itcAnomalyDetected;
		private boolean hardViolationDetected;
		private String engineVersion;
		private LocalDateTime assessmentTimestamp;
		private List<DashboardDTOs.ComplianceAlertDTO> alerts;
	}
}
