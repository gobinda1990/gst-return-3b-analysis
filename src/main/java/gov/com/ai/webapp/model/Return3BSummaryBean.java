package gov.com.ai.webapp.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Return3BSummaryBean {

    /* =========================================================
       BASIC RETURN INFORMATION
       ========================================================= */
    private String gstin;
    private String retPeriod;
    private LocalDate filingDate;
    private LocalDate dueDate;
    private Integer filingDelayDays = 0;
    private String stateCode;

    /* =========================================================
       TABLE 3.1(a) - OUTWARD TAXABLE SUPPLIES
       ========================================================= */
    private BigDecimal taxableValue = BigDecimal.ZERO;
    private BigDecimal outputIgst = BigDecimal.ZERO;
    private BigDecimal outputCgst = BigDecimal.ZERO;
    private BigDecimal outputSgst = BigDecimal.ZERO;
    private BigDecimal outputCess = BigDecimal.ZERO;
    private BigDecimal totalOutputTax = BigDecimal.ZERO;

    /* =========================================================
       TABLE 3.1(b) - ZERO RATED
       ========================================================= */
    private BigDecimal zeroRatedValue = BigDecimal.ZERO;
    private BigDecimal zeroRatedIgst = BigDecimal.ZERO;
    private BigDecimal zeroRatedCgst = BigDecimal.ZERO;
    private BigDecimal zeroRatedSgst = BigDecimal.ZERO;
    private BigDecimal zeroRatedCess = BigDecimal.ZERO;

    /* =========================================================
       TABLE 3.1(c) - NIL / EXEMPT
       ========================================================= */
    private BigDecimal nilExemptValue = BigDecimal.ZERO;
    private BigDecimal nilExemptIgst = BigDecimal.ZERO;
    private BigDecimal nilExemptCgst = BigDecimal.ZERO;
    private BigDecimal nilExemptSgst = BigDecimal.ZERO;
    private BigDecimal nilExemptCess = BigDecimal.ZERO;

    /* =========================================================
       TABLE 3.1(d) - REVERSE CHARGE
       ========================================================= */
    private BigDecimal rcmTaxableValue = BigDecimal.ZERO;
    private BigDecimal rcmIgst = BigDecimal.ZERO;
    private BigDecimal rcmCgst = BigDecimal.ZERO;
    private BigDecimal rcmSgst = BigDecimal.ZERO;
    private BigDecimal rcmCess = BigDecimal.ZERO;
    private BigDecimal rcmTotalTax = BigDecimal.ZERO;

    /* =========================================================
       TABLE 3.1(e) - NON GST
       ========================================================= */
    private BigDecimal nonGstValue = BigDecimal.ZERO;
    private BigDecimal nonGstIgst = BigDecimal.ZERO;
    private BigDecimal nonGstCgst = BigDecimal.ZERO;
    private BigDecimal nonGstSgst = BigDecimal.ZERO;
    private BigDecimal nonGstCess = BigDecimal.ZERO;

    /* =========================================================
       ITC AVAILABLE - IMPG
       ========================================================= */
    private BigDecimal itcImportGoodsIgst = BigDecimal.ZERO;
    private BigDecimal itcImportGoodsCgst = BigDecimal.ZERO;
    private BigDecimal itcImportGoodsSgst = BigDecimal.ZERO;
    private BigDecimal itcImportGoodsCess = BigDecimal.ZERO;

    /* =========================================================
       ITC AVAILABLE - ISRC
       ========================================================= */
    private BigDecimal itcIsrcIgst = BigDecimal.ZERO;
    private BigDecimal itcIsrcCgst = BigDecimal.ZERO;
    private BigDecimal itcIsrcSgst = BigDecimal.ZERO;
    private BigDecimal itcIsrcCess = BigDecimal.ZERO;

    /* =========================================================
       ITC AVAILABLE - OTH
       ========================================================= */
    private BigDecimal itcOthIgst = BigDecimal.ZERO;
    private BigDecimal itcOthCgst = BigDecimal.ZERO;
    private BigDecimal itcOthSgst = BigDecimal.ZERO;
    private BigDecimal itcOthCess = BigDecimal.ZERO;

    /* =========================================================
       ITC AVAILABLE - ISD
       ========================================================= */
    private BigDecimal itcIsdIgst = BigDecimal.ZERO;
    private BigDecimal itcIsdCgst = BigDecimal.ZERO;
    private BigDecimal itcIsdSgst = BigDecimal.ZERO;
    private BigDecimal itcIsdCess = BigDecimal.ZERO;

    /* =========================================================
       ITC AVAILABLE - IMPS
       ========================================================= */
    private BigDecimal itcImpsIgst = BigDecimal.ZERO;
    private BigDecimal itcImpsCgst = BigDecimal.ZERO;
    private BigDecimal itcImpsSgst = BigDecimal.ZERO;
    private BigDecimal itcImpsCess = BigDecimal.ZERO;

    /* =========================================================
       ITC REVERSAL - RUL
       ========================================================= */
    private BigDecimal itcRevRulIgst = BigDecimal.ZERO;
    private BigDecimal itcRevRulCgst = BigDecimal.ZERO;
    private BigDecimal itcRevRulSgst = BigDecimal.ZERO;
    private BigDecimal itcRevRulCess = BigDecimal.ZERO;

    /* =========================================================
       ITC REVERSAL - OTH
       ========================================================= */
    private BigDecimal itcRevOthIgst = BigDecimal.ZERO;
    private BigDecimal itcRevOthCgst = BigDecimal.ZERO;
    private BigDecimal itcRevOthSgst = BigDecimal.ZERO;
    private BigDecimal itcRevOthCess = BigDecimal.ZERO;

    /* =========================================================
       NET ITC
       ========================================================= */
    private BigDecimal netItcIgst = BigDecimal.ZERO;
    private BigDecimal netItcCgst = BigDecimal.ZERO;
    private BigDecimal netItcSgst = BigDecimal.ZERO;
    private BigDecimal netItcCess = BigDecimal.ZERO;
    private BigDecimal netItcTotal = BigDecimal.ZERO;

    /* =========================================================
       INELIGIBLE ITC - RUL
       ========================================================= */
    private BigDecimal ineligibleItcRulIgst = BigDecimal.ZERO;
    private BigDecimal ineligibleItcRulCgst = BigDecimal.ZERO;
    private BigDecimal ineligibleItcRulSgst = BigDecimal.ZERO;
    private BigDecimal ineligibleItcRulCess = BigDecimal.ZERO;

    /* =========================================================
       INELIGIBLE ITC - OTH
       ========================================================= */
    private BigDecimal ineligibleItcOthIgst = BigDecimal.ZERO;
    private BigDecimal ineligibleItcOthCgst = BigDecimal.ZERO;
    private BigDecimal ineligibleItcOthSgst = BigDecimal.ZERO;
    private BigDecimal ineligibleItcOthCess = BigDecimal.ZERO;

    /* =========================================================
       AGGREGATED ITC
       ========================================================= */
    private BigDecimal eligibleItc = BigDecimal.ZERO;
    private BigDecimal utilizedItc = BigDecimal.ZERO;
    private BigDecimal reversedItc = BigDecimal.ZERO;
    private BigDecimal ineligibleItc = BigDecimal.ZERO;
    private BigDecimal excessItc = BigDecimal.ZERO;

    /* =========================================================
       RCM PAYMENT
       ========================================================= */
    private BigDecimal rcmPaymentIgst = BigDecimal.ZERO;
    private BigDecimal rcmPaymentCgst = BigDecimal.ZERO;
    private BigDecimal rcmPaymentSgst = BigDecimal.ZERO;
    private BigDecimal rcmPaymentCess = BigDecimal.ZERO;
    private BigDecimal rcmPaymentTotal = BigDecimal.ZERO;

    /* =========================================================
       CASH PAYMENT
       ========================================================= */
    private BigDecimal cashIgstPaid = BigDecimal.ZERO;
    private BigDecimal cashCgstPaid = BigDecimal.ZERO;
    private BigDecimal cashSgstPaid = BigDecimal.ZERO;
    private BigDecimal cashCessPaid = BigDecimal.ZERO;
    private BigDecimal cashTaxPaid = BigDecimal.ZERO;

    /* =========================================================
       ITC PAYMENT
       ========================================================= */
    private BigDecimal itcPaymentIgst = BigDecimal.ZERO;
    private BigDecimal itcPaymentCgst = BigDecimal.ZERO;
    private BigDecimal itcPaymentSgst = BigDecimal.ZERO;
    private BigDecimal itcPaymentCess = BigDecimal.ZERO;
    private BigDecimal itcPaymentTotal = BigDecimal.ZERO;

    /* =========================================================
       INTEREST & LATE FEES
       ========================================================= */
    private BigDecimal interestIgst = BigDecimal.ZERO;
    private BigDecimal interestCgst = BigDecimal.ZERO;
    private BigDecimal interestSgst = BigDecimal.ZERO;
    private BigDecimal interestCess = BigDecimal.ZERO;
    private BigDecimal interestPaid = BigDecimal.ZERO;

    private BigDecimal lateFeeIgst = BigDecimal.ZERO;
    private BigDecimal lateFeeCgst = BigDecimal.ZERO;
    private BigDecimal lateFeeSgst = BigDecimal.ZERO;
    private BigDecimal lateFeeCess = BigDecimal.ZERO;
    private BigDecimal lateFeePaid = BigDecimal.ZERO;

    private Integer lateFeeApplicable = 0;
    private BigDecimal calculatedInterest = BigDecimal.ZERO;
    private Integer interestApplicable = 0;

    /* =========================================================
       E-COMMERCE
       ========================================================= */
    private BigDecimal ecommerceTurnover = BigDecimal.ZERO;
    private BigDecimal ecommerceIgst = BigDecimal.ZERO;
    private BigDecimal ecommerceCgst = BigDecimal.ZERO;
    private BigDecimal ecommerceSgst = BigDecimal.ZERO;
    private BigDecimal ecommerceCess = BigDecimal.ZERO;

    private BigDecimal ecommerceRegisteredTurnover = BigDecimal.ZERO;
    private BigDecimal ecommerceRegisteredIgst = BigDecimal.ZERO;
    private BigDecimal ecommerceRegisteredCgst = BigDecimal.ZERO;
    private BigDecimal ecommerceRegisteredSgst = BigDecimal.ZERO;
    private BigDecimal ecommerceRegisteredCess = BigDecimal.ZERO;

    /* =========================================================
       DERIVED RATIOS
       ========================================================= */
    private BigDecimal nilSupplyRatio = BigDecimal.ZERO;
    private BigDecimal itcUtilizationRatio = BigDecimal.ZERO;
    private BigDecimal itcToTaxRatio = BigDecimal.ZERO;
    private BigDecimal cashPaymentRatio = BigDecimal.ZERO;
    private BigDecimal itcPaymentRatio = BigDecimal.ZERO;

    private BigDecimal rcmToTaxRatio = BigDecimal.ZERO;
    private BigDecimal rcmItcRatio = BigDecimal.ZERO;
    private BigDecimal rcmCashRatio = BigDecimal.ZERO;

    /* =========================================================
       HISTORICAL (12-MONTH) METRICS & AI TARGETS
       ========================================================= */
    private BigDecimal avgTaxableValue12m = BigDecimal.ZERO;
    private BigDecimal avgOutputTax12m = BigDecimal.ZERO;
    private BigDecimal avgUtilizedItc12m = BigDecimal.ZERO;
    private BigDecimal avgCashPaid12m = BigDecimal.ZERO;

    private BigDecimal taxableValueTrendRatio = BigDecimal.ONE;
    private BigDecimal outputTaxTrendRatio = BigDecimal.ONE;
    private Integer totalLateFilings12m = 0;

    private Boolean lateFiled = Boolean.FALSE;
    private Integer fraudLabel = 0;
    private Double riskScore = 0.0;

    /* =========================================================
       QUESTIONNAIRE
       ========================================================= */
    private String q1;
    private String q2;
    private String q3;
    private String q4;
    private String q5;
    private String q6;
    private String q7;

    /* =========================================================
       AI PREDICTIONS
       ========================================================= */
    private BigDecimal xgbRiskScore = BigDecimal.ZERO;
    private BigDecimal dl4jAnomalyScore = BigDecimal.ZERO;
    private BigDecimal predictedOutputTax = BigDecimal.ZERO;

    /**
     * Fluent normalization instance method.
     */
    public Return3BSummaryBean normalize() {
        if (taxableValue == null) taxableValue = BigDecimal.ZERO;
        if (outputIgst == null) outputIgst = BigDecimal.ZERO;
        if (outputCgst == null) outputCgst = BigDecimal.ZERO;
        if (outputSgst == null) outputSgst = BigDecimal.ZERO;
        if (outputCess == null) outputCess = BigDecimal.ZERO;
        if (totalOutputTax == null) totalOutputTax = BigDecimal.ZERO;

        if (zeroRatedValue == null) zeroRatedValue = BigDecimal.ZERO;
        if (nilExemptValue == null) nilExemptValue = BigDecimal.ZERO;
        if (rcmTotalTax == null) rcmTotalTax = BigDecimal.ZERO;
        if (nonGstValue == null) nonGstValue = BigDecimal.ZERO;

        if (eligibleItc == null) eligibleItc = BigDecimal.ZERO;
        if (utilizedItc == null) utilizedItc = BigDecimal.ZERO;
        if (reversedItc == null) reversedItc = BigDecimal.ZERO;
        if (ineligibleItc == null) ineligibleItc = BigDecimal.ZERO;
        if (excessItc == null) excessItc = BigDecimal.ZERO;

        if (cashTaxPaid == null) cashTaxPaid = BigDecimal.ZERO;
        if (filingDelayDays == null) filingDelayDays = 0;
        if (lateFiled == null) lateFiled = Boolean.FALSE;
        if (totalLateFilings12m == null) totalLateFilings12m = 0;

        if (nilSupplyRatio == null) nilSupplyRatio = BigDecimal.ZERO;
        if (itcUtilizationRatio == null) itcUtilizationRatio = BigDecimal.ZERO;
        if (itcToTaxRatio == null) itcToTaxRatio = BigDecimal.ZERO;
        if (cashPaymentRatio == null) cashPaymentRatio = BigDecimal.ZERO;

        if (avgTaxableValue12m == null) avgTaxableValue12m = BigDecimal.ZERO;
        if (avgOutputTax12m == null) avgOutputTax12m = BigDecimal.ZERO;
        if (avgUtilizedItc12m == null) avgUtilizedItc12m = BigDecimal.ZERO;
        if (avgCashPaid12m == null) avgCashPaid12m = BigDecimal.ZERO;
        if (taxableValueTrendRatio == null) taxableValueTrendRatio = BigDecimal.ONE;
        if (outputTaxTrendRatio == null) outputTaxTrendRatio = BigDecimal.ONE;

        if (xgbRiskScore == null) xgbRiskScore = BigDecimal.ZERO;
        if (dl4jAnomalyScore == null) dl4jAnomalyScore = BigDecimal.ZERO;
        if (predictedOutputTax == null) predictedOutputTax = BigDecimal.ZERO;
        if (riskScore == null) riskScore = 0.0;

        return this;
    }

    /**
     * Static helper method for null-safe stream maps and batch normalization.
     */
    public static Return3BSummaryBean normalize(Return3BSummaryBean bean) {
        if (bean == null) {
            Return3BSummaryBean emptyBean = new Return3BSummaryBean();
            return emptyBean.normalize();
        }
        return bean.normalize();
    }
}