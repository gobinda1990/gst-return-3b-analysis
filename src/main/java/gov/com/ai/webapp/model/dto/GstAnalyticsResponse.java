package gov.com.ai.webapp.model.dto;

import java.time.LocalDate;
import java.util.List;

public record GstAnalyticsResponse(

        String gstin,
        String stateCode,

        int totalReturns,

        LocalDate firstFilingDate,
        LocalDate latestFilingDate,

        GstKpi kpi,

        GstTaxBreakup tax,

        GstItcBreakup itc,

        GstPaymentBreakup payment,

        GstCompliance compliance,

        GstRatioAnalysis ratios,

        GstMlAnalysis ml,

        List<GstMonthlyTrend> monthlyTrend
) {
}