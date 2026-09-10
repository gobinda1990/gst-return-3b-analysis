package gov.com.ai.webapp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import gov.com.ai.webapp.exception.GstDataNotFoundException;
import gov.com.ai.webapp.exception.ItcCalculationException;
import gov.com.ai.webapp.model.ItcDiscrepancyResponse;
import gov.com.ai.webapp.model.ItcSummaryRow;
import gov.com.ai.webapp.repository.ItcDiscrepancyRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Log4j2
public class ItcDiscrepancyEngine {

	private final ItcDiscrepancyRepository repository;
	private final ItcRiskEngine riskEngine;

	public ItcDiscrepancyResponse analyze(String gstin) {

		validateGstin(gstin);

		try {

			List<ItcSummaryRow> history = repository.findLast12Months(gstin);

			if (history.isEmpty()) {
				throw new GstDataNotFoundException("No GSTR-3B summary found for GSTIN");
			}

			/*
			 * Calculate current/latest period.
			 */
			ItcSummaryRow current = history.get(0);

			Calculation currentCalculation = calculate(current);

			int historicalDiscrepancyCount = calculateHistoricalDiscrepancies(history);

			ItcRiskEngine.RiskResult risk = riskEngine.calculate(currentCalculation.potentialExcessItc(),
					currentCalculation.utilizationPercent(), historicalDiscrepancyCount);

			List<String> reasons = generateReasons(current, currentCalculation, historicalDiscrepancyCount);

			return new ItcDiscrepancyResponse(

					current.gstin(), current.retPeriod(),

					safe(current.eligibleItc()), safe(current.reversedItc()), safe(current.ineligibleItc()),
					safe(current.utilizedItc()),

					currentCalculation.netEligibleItc(), currentCalculation.potentialExcessItc(),

					currentCalculation.utilizationPercent(), currentCalculation.itcToTaxPercent(),

					safe(current.rcmTotalTax()), safe(current.rcmTotalItc()),

					currentCalculation.rcmItcPercent(),

					currentCalculation.status(), risk.category(), risk.score(),

					history.size(), historicalDiscrepancyCount,

					reasons);

		} catch (GstDataNotFoundException ex) {

			throw ex;

		} catch (Exception ex) {

			log.error("ITC discrepancy calculation failed for GSTIN={}", gstin, ex);

			throw new ItcCalculationException("Unable to calculate ITC discrepancy");
		}
	}

	private Calculation calculate(ItcSummaryRow row) {

		BigDecimal eligible = safe(row.eligibleItc());

		BigDecimal reversed = safe(row.reversedItc());

		BigDecimal ineligible = safe(row.ineligibleItc());

		BigDecimal utilized = safe(row.utilizedItc());

		BigDecimal netEligible = ItcCalculationUtil.netEligibleItc(eligible, reversed, ineligible);

		BigDecimal excess = ItcCalculationUtil.potentialExcessItc(utilized, netEligible);

		BigDecimal utilization = ItcCalculationUtil.percentage(utilized, netEligible);

		BigDecimal itcToTax = ItcCalculationUtil.percentage(utilized, safe(row.totalOutputTax()));

		BigDecimal rcmPercent = ItcCalculationUtil.percentage(safe(row.rcmTotalItc()), safe(row.rcmTotalTax()));

		String status = excess.compareTo(BigDecimal.ZERO) > 0 ? "POTENTIAL_EXCESS_ITC" : "NO_DISCREPANCY";

		return new Calculation(netEligible, excess, utilization, itcToTax, rcmPercent, status);
	}

	private int calculateHistoricalDiscrepancies(List<ItcSummaryRow> history) {

		int count = 0;

		for (ItcSummaryRow row : history) {

			BigDecimal netEligible = ItcCalculationUtil.netEligibleItc(row.eligibleItc(), row.reversedItc(),
					row.ineligibleItc());

			BigDecimal excess = ItcCalculationUtil.potentialExcessItc(row.utilizedItc(), netEligible);

			if (excess.compareTo(BigDecimal.ZERO) > 0) {
				count++;
			}
		}

		return count;
	}

	private List<String> generateReasons(ItcSummaryRow row, Calculation calculation, int historicalCount) {

		List<String> reasons = new ArrayList<>();

		if (calculation.potentialExcessItc().compareTo(BigDecimal.ZERO) > 0) {

			reasons.add("Potential ITC utilization exceeds calculated net eligible ITC");
		}

		if (safe(row.reversedItc()).compareTo(BigDecimal.ZERO) > 0) {

			reasons.add("ITC reversal reported in the return");
		}

		if (safe(row.ineligibleItc()).compareTo(BigDecimal.ZERO) > 0) {

			reasons.add("Ineligible ITC reported in the return");
		}

		if (historicalCount >= 3) {

			reasons.add("ITC discrepancy observed repeatedly during historical review");
		}

		if (calculation.rcmItcPercent().compareTo(BigDecimal.valueOf(100)) > 0) {

			reasons.add("RCM ITC exceeds reported RCM tax for the period; reconciliation required");
		}

		if (reasons.isEmpty()) {
			reasons.add("No material ITC discrepancy identified");
		}

		return List.copyOf(reasons);
	}

	private void validateGstin(String gstin) {

		if (gstin == null || !gstin.matches("^[0-9]{2}[A-Z0-9]{13}$")) {

			throw new IllegalArgumentException("Invalid GSTIN");
		}
	}

	private BigDecimal safe(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

	private record Calculation(BigDecimal netEligibleItc, BigDecimal potentialExcessItc, BigDecimal utilizationPercent,
			BigDecimal itcToTaxPercent, BigDecimal rcmItcPercent, String status) {
	}
}
