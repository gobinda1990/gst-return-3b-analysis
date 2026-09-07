package gov.com.ai.webapp.service;

import gov.com.ai.webapp.manager.GstAiModelTrainer;
import gov.com.ai.webapp.manager.TrainingDataset;
import gov.com.ai.webapp.manager.XGBoostModelManager;
import gov.com.ai.webapp.model.GstMlDto.*;
import gov.com.ai.webapp.model.Return3BSummaryBean;
import gov.com.ai.webapp.repository.Return3bRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class GstPredictionService {

    private final Return3bRepository return3bRepository;
    private final XGBoostModelManager modelManager;

    private static final int HISTORICAL_MONTHS_LOOKBACK = 12;
    private static final double RUPEES_TO_LAKHS = 100_000.0;
    private static final double LATE_FEE_PER_DAY_STANDARD = 50.0;
    
    // Strict Multiplier Guardrail: AI Prediction cannot exceed 3x the max historical monthly value
    private static final double MAX_HISTORICAL_MULTIPLIER_CAP = 3.0;

    public PredictionResponse predictNextMonth(PredictionRequest request) {
        String cleanGstin = request.getGstin().trim().toUpperCase();
        log.info("[Inference Engine] Computing GST predictions dynamically for GSTIN: [{}]", cleanGstin);

        List<Return3BSummaryBean> historyBeans = return3bRepository.findHistory(cleanGstin, HISTORICAL_MONTHS_LOOKBACK);

        if (historyBeans != null && !historyBeans.isEmpty()) {
            historyBeans.sort(Comparator.comparing(b -> b.normalize().getRetPeriod(), Comparator.nullsFirst(String::compareTo)));
        }

        YearMonth targetReturnPeriod = deriveNextReturnPeriod(historyBeans);
        String returnPeriodStr = targetReturnPeriod.format(DateTimeFormatter.ofPattern("MMyyyy"));

        boolean isNilHistory = isPureNilHistory(historyBeans);

        // 1. Build composite bean with values normalized explicitly to Lakhs for 71-feature extraction
        Return3BSummaryBean compositeBeanLakhs = buildCompositeBeanInLakhs(cleanGstin, historyBeans);
        float[] raw71Features = TrainingDataset.extractFeatureVector(compositeBeanLakhs);

        // 2. Model Inference Calls (Model outputs values in Lakhs)
        Float predOutputTaxLakhs = modelManager.runInference(GstAiModelTrainer.MODEL_NAME_OUTPUT_TAX, raw71Features);
        Float predItcLakhs = modelManager.runInference(GstAiModelTrainer.MODEL_NAME_ITC, raw71Features);
        Float predDelayDays = modelManager.runInference(GstAiModelTrainer.MODEL_NAME_DELAY, raw71Features);
        Float predRiskScore = modelManager.runInference(GstAiModelTrainer.MODEL_NAME_RISK, raw71Features);

        // 3. Trailing Historical Metrics (Calculated in Absolute Rupees)
        double avgOutputRs = calculateAvgOutputTaxRs(historyBeans);
        double maxHistoricalOutputRs = calculateMaxOutputTaxRs(historyBeans);
        double avgItcRs = calculateAvgUtilizedItcRs(historyBeans);

        // 4. Sanitize and Convert Model Predictions from Lakhs to Rupees
        double predictedOutputTaxRs = sanitizePredictionRs(predOutputTaxLakhs, avgOutputRs, maxHistoricalOutputRs, isNilHistory);
        double predictedItcRs = sanitizePredictionRs(predItcLakhs, avgItcRs, maxHistoricalOutputRs, isNilHistory);
        
        // Logical constraint: Predicted ITC cannot exceed predicted Output Tax
        predictedItcRs = Math.min(predictedItcRs, predictedOutputTaxRs);
        double forecastedCashRs = Math.max(0.0, predictedOutputTaxRs - predictedItcRs);

        float finalDelay = (predDelayDays != null && !predDelayDays.isNaN() && !predDelayDays.isInfinite()) 
                ? Math.max(0.0f, predDelayDays) 
                : calculateWeightedRecentDelay(historyBeans);

        float finalRisk = (predRiskScore != null && !predRiskScore.isNaN()) 
                ? Math.min(1.0f, Math.max(0.0f, predRiskScore)) 
                : deriveHeuristicRiskScore(historyBeans, finalDelay);

        // 5. Derive Dynamic Effective Tax Rate & Turnover
        double effectiveTaxRate = deriveEffectiveTaxRate(historyBeans);
        double estimatedTaxableValue = (effectiveTaxRate > 0) 
                ? Math.round((predictedOutputTaxRs / effectiveTaxRate) * 100.0) / 100.0 
                : predictedOutputTaxRs;

        double itcRatio = predictedOutputTaxRs > 0 ? (predictedItcRs / predictedOutputTaxRs) : 0.0;

        // 6. Statutory Schedule Calculations
        LocalDate statutoryDueDate = targetReturnPeriod.plusMonths(1).atDay(20);
        int roundedDelayDays = Math.round(finalDelay);
        LocalDate estimatedFilingDate = statutoryDueDate.plusDays(roundedDelayDays);
        double calculatedLateFee = roundedDelayDays * LATE_FEE_PER_DAY_STANDARD;

        String riskCategory = deriveRiskCategory(finalRisk);
        String primaryRiskFactor = derivePrimaryRiskFactor(compositeBeanLakhs, finalRisk, roundedDelayDays);

        CashLiabilityForecast forecast = CashLiabilityForecast.builder()
                .returnPeriod(returnPeriodStr)
                .predictedTaxValue(predictedOutputTaxRs)
                .predictedItcValue(predictedItcRs)
                .predictedCashValue(forecastedCashRs)
                .build();

        return PredictionResponse.builder()
                .gstin(cleanGstin)
                .predictionPeriod(returnPeriodStr)
                .targetRetPeriod(returnPeriodStr)
                .riskCategory(riskCategory)
                .riskTrend(riskCategory)
                .lateFilingRiskScore((double) finalRisk)
                .probabilityOfDefault((double) finalRisk)
                .defaultPrediction(finalRisk > 0.60f)
                .defaultPredicted(finalRisk > 0.60f)
                .predictionSource(modelManager.isFullyOperational() ? "XGBoost-V2" : "HISTORICAL_HYBRID")
                .modelVersion("2.1.0")
                .primaryRiskFactor(primaryRiskFactor)
                .dueDate(statutoryDueDate.toString())
                .estimatedFilingDate(estimatedFilingDate.toString())
                .delayDays(roundedDelayDays)
                .calculatedLateFee(calculatedLateFee)
                .calculatedDt(LocalDate.now().toString())
                .predictedTaxableVal(estimatedTaxableValue)
                .predictedOutputTax(predictedOutputTaxRs)
                .predictedItcAvail(predictedItcRs)
                .predictedItcRatio(itcRatio)
                .forecastedCashLiability(Collections.singletonList(forecast))
                .build();
    }

    /**
     * Sanitizes raw machine learning output (in Lakhs) and converts to Rupees, 
     * enforcing absolute boundary caps based on actual historical data.
     */
    private double sanitizePredictionRs(Float rawModelLakhs, double avgRs, double maxHistoricalRs, boolean isNilHistory) {
        if (isNilHistory) {
            return 0.0;
        }

        if (rawModelLakhs == null || rawModelLakhs.isNaN() || rawModelLakhs.isInfinite() || rawModelLakhs <= 0) {
            return avgRs;
        }

        double predictedRs = rawModelLakhs * RUPEES_TO_LAKHS;

        // Hard Upper Cap: Cannot exceed 3x the maximum historical monthly output tax (or minimum baseline ₹50,000)
        double upperCapRs = Math.max(maxHistoricalRs * MAX_HISTORICAL_MULTIPLIER_CAP, 50_000.0);

        if (predictedRs > upperCapRs) {
            log.warn("[AI Safeguard] Capping anomalous output: ₹{} to historical safe cap: ₹{}", predictedRs, upperCapRs);
            return Math.round(upperCapRs * 100.0) / 100.0;
        }

        return Math.round(predictedRs * 100.0) / 100.0;
    }

    /**
     * Constructs a composite bean with all financial metrics scaled down to Lakhs,
     * ensuring feature inputs match training scale expectations.
     */
    private Return3BSummaryBean buildCompositeBeanInLakhs(String gstin, List<Return3BSummaryBean> historyBeans) {
        Return3BSummaryBean bean = new Return3BSummaryBean();
        bean.setGstin(gstin);

        if (historyBeans == null || historyBeans.isEmpty()) {
            Return3BSummaryBean.normalize(bean);
            return bean;
        }

        Return3BSummaryBean latest = historyBeans.get(historyBeans.size() - 1).normalize();
        bean.setRetPeriod(latest.getRetPeriod());
        
        // Scale latest period values to Lakhs
        bean.setTaxableValue(toLakhs(latest.getTaxableValue()));
        bean.setTotalOutputTax(toLakhs(latest.getTotalOutputTax()));
        bean.setEligibleItc(toLakhs(latest.getEligibleItc()));
        bean.setUtilizedItc(toLakhs(latest.getUtilizedItc()));
        bean.setCashTaxPaid(toLakhs(latest.getCashTaxPaid()));
        bean.setFilingDelayDays(latest.getFilingDelayDays());

        int count = historyBeans.size();
        double sumTaxable = 0.0;
        double sumOutput = 0.0;
        double sumItc = 0.0;
        double sumCash = 0.0;

        for (Return3BSummaryBean raw : historyBeans) {
            Return3BSummaryBean norm = raw.normalize();
            sumTaxable += norm.getTaxableValue() != null ? norm.getTaxableValue().doubleValue() / RUPEES_TO_LAKHS : 0.0;
            sumOutput += norm.getTotalOutputTax() != null ? norm.getTotalOutputTax().doubleValue() / RUPEES_TO_LAKHS : 0.0;
            sumItc += norm.getUtilizedItc() != null ? norm.getUtilizedItc().doubleValue() / RUPEES_TO_LAKHS : 0.0;
            sumCash += norm.getCashTaxPaid() != null ? norm.getCashTaxPaid().doubleValue() / RUPEES_TO_LAKHS : 0.0;
        }

        bean.setAvgTaxableValue12m(BigDecimal.valueOf(sumTaxable / count));
        bean.setAvgOutputTax12m(BigDecimal.valueOf(sumOutput / count));
        bean.setAvgUtilizedItc12m(BigDecimal.valueOf(sumItc / count));
        bean.setAvgCashPaid12m(BigDecimal.valueOf(sumCash / count));

        double avgTaxable = bean.getAvgTaxableValue12m().doubleValue();
        if (avgTaxable > 0) {
            double trend = bean.getTaxableValue().doubleValue() / avgTaxable;
            bean.setTaxableValueTrendRatio(BigDecimal.valueOf(trend));
        } else {
            bean.setTaxableValueTrendRatio(BigDecimal.valueOf(1.0));
        }

        Return3BSummaryBean.normalize(bean);
        return bean;
    }

    private BigDecimal toLakhs(BigDecimal rupees) {
        if (rupees == null) return BigDecimal.ZERO;
        return rupees.divide(BigDecimal.valueOf(RUPEES_TO_LAKHS), 6, RoundingMode.HALF_UP);
    }

    private boolean isPureNilHistory(List<Return3BSummaryBean> history) {
        if (history == null || history.isEmpty()) return true;
        return history.stream().allMatch(b -> {
            Return3BSummaryBean norm = b.normalize();
            double tax = norm.getTotalOutputTax() != null ? norm.getTotalOutputTax().doubleValue() : 0.0;
            double taxable = norm.getTaxableValue() != null ? norm.getTaxableValue().doubleValue() : 0.0;
            return tax == 0.0 && taxable == 0.0;
        });
    }

    private double calculateAvgOutputTaxRs(List<Return3BSummaryBean> history) {
        if (history == null || history.isEmpty()) return 0.0;
        return history.stream()
                .mapToDouble(b -> b.normalize().getTotalOutputTax() != null ? b.normalize().getTotalOutputTax().doubleValue() : 0.0)
                .average()
                .orElse(0.0);
    }

    private double calculateMaxOutputTaxRs(List<Return3BSummaryBean> history) {
        if (history == null || history.isEmpty()) return 0.0;
        return history.stream()
                .mapToDouble(b -> b.normalize().getTotalOutputTax() != null ? b.normalize().getTotalOutputTax().doubleValue() : 0.0)
                .max()
                .orElse(0.0);
    }

    private double calculateAvgUtilizedItcRs(List<Return3BSummaryBean> history) {
        if (history == null || history.isEmpty()) return 0.0;
        return history.stream()
                .mapToDouble(b -> b.normalize().getUtilizedItc() != null ? b.normalize().getUtilizedItc().doubleValue() : 0.0)
                .average()
                .orElse(0.0);
    }

    private YearMonth deriveNextReturnPeriod(List<Return3BSummaryBean> history) {
        if (history != null && !history.isEmpty()) {
            Return3BSummaryBean latest = history.get(history.size() - 1).normalize();
            String retPeriod = latest.getRetPeriod();
            if (retPeriod != null && retPeriod.length() == 6) {
                try {
                    int month = Integer.parseInt(retPeriod.substring(0, 2));
                    int year = Integer.parseInt(retPeriod.substring(2, 6));
                    return YearMonth.of(year, month).plusMonths(1);
                } catch (Exception ignored) {}
            }
        }
        return YearMonth.now().plusMonths(1);
    }

    private double deriveEffectiveTaxRate(List<Return3BSummaryBean> history) {
        if (history == null || history.isEmpty()) return 0.18;

        double totalTaxable = 0.0;
        double totalOutput = 0.0;

        for (Return3BSummaryBean rawBean : history) {
            Return3BSummaryBean bean = rawBean.normalize();
            totalTaxable += bean.getTaxableValue() != null ? bean.getTaxableValue().doubleValue() : 0.0;
            totalOutput += bean.getTotalOutputTax() != null ? bean.getTotalOutputTax().doubleValue() : 0.0;
        }

        if (totalTaxable > 0) {
            double rate = totalOutput / totalTaxable;
            return (rate > 0.01 && rate < 0.40) ? rate : 0.18;
        }

        return 0.18;
    }

    private float calculateWeightedRecentDelay(List<Return3BSummaryBean> history) {
        if (history == null || history.isEmpty()) return 0.0f;
        int size = history.size();
        int sampleSize = Math.min(3, size);
        double recentSum = 0.0;
        
        for (int i = size - sampleSize; i < size; i++) {
            recentSum += history.get(i).normalize().getFilingDelayDays();
        }
        return (float) (recentSum / sampleSize);
    }

    private float deriveHeuristicRiskScore(List<Return3BSummaryBean> history, float weightedDelay) {
        if (history == null || history.isEmpty()) return 0.15f;
        long delayedCount = history.stream().filter(b -> b.normalize().getFilingDelayDays() > 0).count();
        float delayRatio = (float) delayedCount / history.size();
        if (delayRatio > 0.80f || weightedDelay > 20.0f) return 0.78f;
        if (delayRatio > 0.40f) return 0.45f;
        return 0.20f;
    }

    private String deriveRiskCategory(float riskScore) {
        if (riskScore >= 0.70f) return "HIGH";
        if (riskScore >= 0.35f) return "MEDIUM";
        return "LOW";
    }

    private String derivePrimaryRiskFactor(Return3BSummaryBean compositeBean, float riskScore, int delayDays) {
        if (delayDays > 15) return "CHRONIC_FILING_DELAY";
        if (compositeBean.getItcToTaxRatio() != null && compositeBean.getItcToTaxRatio().doubleValue() > 0.95) {
            return "ITC_ELIGIBILITY_MISMATCH";
        }
        if (compositeBean.getTaxableValueTrendRatio() != null && compositeBean.getTaxableValueTrendRatio().doubleValue() > 1.5) {
            return "OUTWARD_TURNOVER_MISMATCH";
        }
        if (riskScore > 0.50f) return "TURNOVER_VOLATILITY";
        return "NONE_DETECTED";
    }
}