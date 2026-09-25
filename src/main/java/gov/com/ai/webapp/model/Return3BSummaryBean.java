package gov.com.ai.webapp.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * =====================================================================
 * GSTR-3B SUMMARY BEAN - Enhanced with Risk Assessment Fields
 * =====================================================================
 *
 * Represents a parsed and calculated GSTR-3B return summary extracted from
 * the GSTN portal JSON payload. This bean aggregates all key financial figures,
 * tax amounts, ITC details, and derived metrics required for:
 *
 *   1. Database persistence (GST_RET_3B_SUMMARY table)
 *   2. Risk assessment (GST_RET_3B_RISK_ASSESSMENT service)
 *   3. AI model feature engineering (XGBoost, DL4J scoring)
 *   4. Scrutiny officer dashboards and analytics
 *
 * Version: 1.1 (Enhanced with calculated late fee and better ratio handling)
 *
 * Field Naming Convention:
 *   - Prefixes indicate tax type: output*, rcm*, cash*, itc*, net*, ineligible*
 *   - Suffixes indicate GST component: Igst, Cgst, Sgst, Cess
 *   - Aggregated totals: *Total (e.g., totalOutputTax, rcmTotalTax)
 *   - Ratios: *Ratio (always 0-1 scale, 4 decimal places)
 *   - Flags: *Applicable (1/0 integer), *Detected (Y/N character)
 *   - Calculated: calculated* (e.g., calculatedInterest, calculatedLateFee)
 *
 * PRECISION:
 *   - Financial fields (amounts): 2 decimal places (Rs. format)
 *   - Ratios: 4 decimal places (0.0000 to 9.9999, capped)
 *   - Scores (AI models): 4 decimal places (0.0000 to 1.0000)
 *
 * NULL HANDLING:
 *   - All numeric fields default to BigDecimal.ZERO (never null)
 *   - String fields (Q-flags, GST components) default to null (optional)
 *   - All fields have explicit initialization in constructors/defaults
 */
@Slf4j
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Return3BSummaryBean {

    /* =========================================================
       SECTION 1: BASIC RETURN INFORMATION
       ========================================================= */

    /** GSTIN of the taxpayer (15 chars, format: NNLTTTTT0000N0) */
    private String gstin;

    /** Return period in MMYYYY format (e.g., "122025" for Dec 2025) */
    private String retPeriod;

    /** Date when return was filed (parsed from fil_dt in GSTR-3B JSON) */
    private LocalDate filingDate;

    /** Statutory due date (20th of following month for monthly filers) */
    private LocalDate dueDate;

    /** Filing delay in days (calculated as max(0, filingDate - dueDate)) */
    private Integer filingDelayDays = 0;

    /** State code extracted from GSTIN (first 2 digits) */
    private String stateCode;

    /* =========================================================
       SECTION 2: OUTWARD TAXABLE SUPPLIES (Table 3.1(a))
       ========================================================= */

    private BigDecimal taxableValue = BigDecimal.ZERO;
    private BigDecimal outputIgst = BigDecimal.ZERO;
    private BigDecimal outputCgst = BigDecimal.ZERO;
    private BigDecimal outputSgst = BigDecimal.ZERO;
    private BigDecimal outputCess = BigDecimal.ZERO;
    private BigDecimal totalOutputTax = BigDecimal.ZERO;

    /* =========================================================
       SECTION 3: ZERO RATED SUPPLIES (Table 3.1(b))
       ========================================================= */

    private BigDecimal zeroRatedValue = BigDecimal.ZERO;
    private BigDecimal zeroRatedIgst = BigDecimal.ZERO;
    private BigDecimal zeroRatedCgst = BigDecimal.ZERO;
    private BigDecimal zeroRatedSgst = BigDecimal.ZERO;
    private BigDecimal zeroRatedCess = BigDecimal.ZERO;

    /* =========================================================
       SECTION 4: NIL / EXEMPT SUPPLIES (Table 3.1(c))
       ========================================================= */

    private BigDecimal nilExemptValue = BigDecimal.ZERO;
    private BigDecimal nilExemptIgst = BigDecimal.ZERO;
    private BigDecimal nilExemptCgst = BigDecimal.ZERO;
    private BigDecimal nilExemptSgst = BigDecimal.ZERO;
    private BigDecimal nilExemptCess = BigDecimal.ZERO;

    /* =========================================================
       SECTION 5: REVERSE CHARGE / RCM INBOUND (Table 3.1(d))
       
       Per Sec 9(3)/9(4) CGST Act: Supplier does not charge tax;
       recipient liable to pay RCM (Reverse Charge Mechanism) tax.
       ========================================================= */

    private BigDecimal rcmTaxableValue = BigDecimal.ZERO;
    private BigDecimal rcmIgst = BigDecimal.ZERO;
    private BigDecimal rcmCgst = BigDecimal.ZERO;
    private BigDecimal rcmSgst = BigDecimal.ZERO;
    private BigDecimal rcmCess = BigDecimal.ZERO;
    private BigDecimal rcmTotalTax = BigDecimal.ZERO;

    /* =========================================================
       SECTION 6: NON-GST SUPPLIES (Table 3.1(e))
       ========================================================= */

    private BigDecimal nonGstValue = BigDecimal.ZERO;
    private BigDecimal nonGstIgst = BigDecimal.ZERO;
    private BigDecimal nonGstCgst = BigDecimal.ZERO;
    private BigDecimal nonGstSgst = BigDecimal.ZERO;
    private BigDecimal nonGstCess = BigDecimal.ZERO;

    /* =========================================================
       SECTION 7: ITC AVAILABLE - IMPORT GOODS (IMPG)
       
       Per Sec 16(1): ITC on import of goods as per Bill of Entry.
       ========================================================= */

    private BigDecimal itcImportGoodsIgst = BigDecimal.ZERO;
    private BigDecimal itcImportGoodsCgst = BigDecimal.ZERO;
    private BigDecimal itcImportGoodsSgst = BigDecimal.ZERO;
    private BigDecimal itcImportGoodsCess = BigDecimal.ZERO;

    /* =========================================================
       SECTION 8: ITC AVAILABLE - ISRC (ISRC)
       
       Per Sec 16(2)(c): ITC on inward supplies received under
       Reverse Charge Mechanism. Subject to condition that RCM tax
       is actually paid (Sec 16(4)).
       ========================================================= */

    private BigDecimal itcIsrcIgst = BigDecimal.ZERO;
    private BigDecimal itcIsrcCgst = BigDecimal.ZERO;
    private BigDecimal itcIsrcSgst = BigDecimal.ZERO;
    private BigDecimal itcIsrcCess = BigDecimal.ZERO;

    /* =========================================================
       SECTION 9: ITC AVAILABLE - OTHER (OTH)
       
       Per Sec 16: ITC on inward supplies (invoices, documents)
       that meet eligibility conditions under Sec 16 & Rule 36.
       ========================================================= */

    private BigDecimal itcOthIgst = BigDecimal.ZERO;
    private BigDecimal itcOthCgst = BigDecimal.ZERO;
    private BigDecimal itcOthSgst = BigDecimal.ZERO;
    private BigDecimal itcOthCess = BigDecimal.ZERO;

    /* =========================================================
       SECTION 10: ITC AVAILABLE - ISD (ISD)
       
       Per Sec 16(1)(c): ITC on purchases from Input Service
       Distributors (authorized intermediaries).
       ========================================================= */

    private BigDecimal itcIsdIgst = BigDecimal.ZERO;
    private BigDecimal itcIsdCgst = BigDecimal.ZERO;
    private BigDecimal itcIsdSgst = BigDecimal.ZERO;
    private BigDecimal itcIsdCess = BigDecimal.ZERO;

    /* =========================================================
       SECTION 11: ITC AVAILABLE - IMPS (IMPS)
       
       Per Sec 16(1)(d): ITC on import of services as per Bill of
       Entry or invoice from service supplier.
       ========================================================= */

    private BigDecimal itcImpsIgst = BigDecimal.ZERO;
    private BigDecimal itcImpsCgst = BigDecimal.ZERO;
    private BigDecimal itcImpsSgst = BigDecimal.ZERO;
    private BigDecimal itcImpsCess = BigDecimal.ZERO;

    /* =========================================================
       SECTION 12: ITC REVERSAL - RULE-BASED (RUL)
       
       Per Rule 42 CGST Rules: Reversal of ITC on inputs used for
       exempt supply, non-business purpose, or blocked under Sec 17.
       ========================================================= */

    private BigDecimal itcRevRulIgst = BigDecimal.ZERO;
    private BigDecimal itcRevRulCgst = BigDecimal.ZERO;
    private BigDecimal itcRevRulSgst = BigDecimal.ZERO;
    private BigDecimal itcRevRulCess = BigDecimal.ZERO;

    /* =========================================================
       SECTION 13: ITC REVERSAL - OTHER (OTH)
       
       Per Sec 17: Reversal of ITC on personal consumption,
       blocked goods/services, or other ineligibility.
       ========================================================= */

    private BigDecimal itcRevOthIgst = BigDecimal.ZERO;
    private BigDecimal itcRevOthCgst = BigDecimal.ZERO;
    private BigDecimal itcRevOthSgst = BigDecimal.ZERO;
    private BigDecimal itcRevOthCess = BigDecimal.ZERO;

    /* =========================================================
       SECTION 14: NET ITC (Portal Calculated)
       
       Per GSTN auto-match (post-2022 CGST Act amendment):
       NET ITC = Available ITC - Reversed ITC (as per GSTR-2B match)
       ========================================================= */

    private BigDecimal netItcIgst = BigDecimal.ZERO;
    private BigDecimal netItcCgst = BigDecimal.ZERO;
    private BigDecimal netItcSgst = BigDecimal.ZERO;
    private BigDecimal netItcCess = BigDecimal.ZERO;
    private BigDecimal netItcTotal = BigDecimal.ZERO;

    /* =========================================================
       SECTION 15: INELIGIBLE ITC - RULE-BASED (RUL)
       
       Per Rule 41/43 CGST Rules: ITC rejected by GSTN auto-match
       or manually blocked by officer due to supplier non-payment,
       invalid documentation, or rule violation.
       ========================================================= */

    private BigDecimal ineligibleItcRulIgst = BigDecimal.ZERO;
    private BigDecimal ineligibleItcRulCgst = BigDecimal.ZERO;
    private BigDecimal ineligibleItcRulSgst = BigDecimal.ZERO;
    private BigDecimal ineligibleItcRulCess = BigDecimal.ZERO;

    /* =========================================================
       SECTION 16: INELIGIBLE ITC - OTHER (OTH)
       
       Per Sec 17(5): ITC denied due to personal use, blocked
       supplies, or other ineligibility under GST Act.
       ========================================================= */

    private BigDecimal ineligibleItcOthIgst = BigDecimal.ZERO;
    private BigDecimal ineligibleItcOthCgst = BigDecimal.ZERO;
    private BigDecimal ineligibleItcOthSgst = BigDecimal.ZERO;
    private BigDecimal ineligibleItcOthCess = BigDecimal.ZERO;

    /* =========================================================
       SECTION 17: AGGREGATED ITC TOTALS
       
       ELIGIBLE_ITC = sum(IMPG + ISRC + OTH + ISD + IMPS)
       REVERSED_ITC = sum(RUL + OTH reversals)
       NET_AVAILABLE = ELIGIBLE - REVERSED
       UTILIZED_ITC = actual ITC used to discharge liability (Sec 49A/49B)
       INELIGIBLE_ITC = sum(RUL + OTH ineligible)
       EXCESS_ITC = max(0, UTILIZED - NET_AVAILABLE)  ← Risk flag per Sec 16/17
       ========================================================= */

    private BigDecimal eligibleItc = BigDecimal.ZERO;
    private BigDecimal utilizedItc = BigDecimal.ZERO;
    private BigDecimal reversedItc = BigDecimal.ZERO;
    private BigDecimal ineligibleItc = BigDecimal.ZERO;
    private BigDecimal excessItc = BigDecimal.ZERO;

    /* =========================================================
       SECTION 18: RCM TAX PAYMENT
       
       Per Sec 49(4) CGST Act: RCM tax is mandatory cash payment
       (cannot be discharged via ITC). Tracked separately from
       normal cash payment.
       ========================================================= */

    private BigDecimal rcmPaymentIgst = BigDecimal.ZERO;
    private BigDecimal rcmPaymentCgst = BigDecimal.ZERO;
    private BigDecimal rcmPaymentSgst = BigDecimal.ZERO;
    private BigDecimal rcmPaymentCess = BigDecimal.ZERO;
    private BigDecimal rcmPaymentTotal = BigDecimal.ZERO;

    /* =========================================================
       SECTION 19: NORMAL TAX - CASH PAYMENT
       
       Per Sec 49: Cash paid towards normal output tax liability
       (excluding RCM). May be combined with ITC to discharge
       total liability.
       ========================================================= */

    private BigDecimal cashIgstPaid = BigDecimal.ZERO;
    private BigDecimal cashCgstPaid = BigDecimal.ZERO;
    private BigDecimal cashSgstPaid = BigDecimal.ZERO;
    private BigDecimal cashCessPaid = BigDecimal.ZERO;
    private BigDecimal cashTaxPaid = BigDecimal.ZERO;

    /* =========================================================
       SECTION 20: NORMAL TAX - ITC PAYMENT (Utilization)
       
       Per Sec 49A/49B CGST Act: Utilization order for discharge of
       tax liability:
         1. IGST liability: IGST credit first, then CGST, then SGST
         2. CGST liability: IGST or CGST credit (NOT SGST)
         3. SGST liability: IGST or SGST credit (NOT CGST)
         4. CESS: CESS credit only
       ========================================================= */

    private BigDecimal itcPaymentIgst = BigDecimal.ZERO;
    private BigDecimal itcPaymentCgst = BigDecimal.ZERO;
    private BigDecimal itcPaymentSgst = BigDecimal.ZERO;
    private BigDecimal itcPaymentCess = BigDecimal.ZERO;
    private BigDecimal itcPaymentTotal = BigDecimal.ZERO;

    /* =========================================================
       SECTION 21: INTEREST ON DELAYED PAYMENT
       
       Per Sec 50(1) CGST Act: Interest at 18% p.a. on unpaid tax
       (floored to zero if no tax is unpaid due to advance payment/ITC).
       
       Per Sec 50(3): Interest at 24% p.a. if fraud/willful misstatement.
       
       Unpaid Tax = Output Tax - ITC Payment - Cash Payment (floored to 0)
       Calculated Interest = Unpaid × 0.18 × FilingDelayDays / 365
       ========================================================= */

    private BigDecimal interestIgst = BigDecimal.ZERO;
    private BigDecimal interestCgst = BigDecimal.ZERO;
    private BigDecimal interestSgst = BigDecimal.ZERO;
    private BigDecimal interestCess = BigDecimal.ZERO;
    private BigDecimal interestPaid = BigDecimal.ZERO;

    /* =========================================================
       SECTION 22: LATE FEE ON DELAYED FILING
       
       Per Sec 47 CGST Act:
         - NIL return: Rs. 20/day (max Rs. 10,000)
         - Non-NIL return: Rs. 50/day (max Rs. 10,000)
       
       Calculated Late Fee = min(FilingDelayDays × Rate, 10,000)
       
       Note: Q-flags determine if return is NIL (typically Q1='Y')
       ========================================================= */

    private BigDecimal lateFeeIgst = BigDecimal.ZERO;
    private BigDecimal lateFeeCgst = BigDecimal.ZERO;
    private BigDecimal lateFeeSgst = BigDecimal.ZERO;
    private BigDecimal lateFeeCess = BigDecimal.ZERO;
    private BigDecimal lateFeePaid = BigDecimal.ZERO;

    /* =========================================================
       SECTION 23: COMPLIANCE FLAGS & CALCULATED VALUES
       ========================================================= */

    /**
     * Flag indicating if return was filed late (1=yes, 0=no).
     * Per Sec 47, late filing triggers late fee liability.
     */
    private Integer lateFeeApplicable = 0;

    /**
     * Calculated interest per Sec 50(1) @ 18% p.a. on unpaid tax.
     * Only non-zero if: filingDelayDays > 0 AND unpaid tax > 0
     */
    private BigDecimal calculatedInterest = BigDecimal.ZERO;

    /**
     * Flag indicating if interest is applicable (1=yes, 0=no).
     * Same as lateFeeApplicable but separate for audit trail.
     */
    private Integer interestApplicable = 0;

    /**
     * Calculated late fee per Sec 47.
     * Only non-zero if: filingDelayDays > 0
     * Amount = min(delayDays × ratePerDay, 10,000)
     * (NEW FIELD - added in v1.1)
     */
    private BigDecimal calculatedLateFee = BigDecimal.ZERO;

    /* =========================================================
       SECTION 24: E-COMMERCE OPERATOR TCS
       
       Per Sec 52: Tax collected at source (TCS) by e-commerce
       operators on supplies through their platform.
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
       SECTION 25: DERIVED RISK ASSESSMENT RATIOS (0-1 scale)
       
       All ratios capped at 9.9999 per SQL schema definition.
       Stored with 4 decimal places for AI model features.
       ========================================================= */

    /**
     * NIL_SUPPLY_RATIO = nilExemptValue / (taxableValue + nilExemptValue)
     * Per Rule 42/43: High ratio (>= 0.70) with zero reversal = risk
     */
    private BigDecimal nilSupplyRatio = BigDecimal.ZERO;

    /**
     * ITC_UTILIZATION_RATIO = utilizedItc / eligibleItc
     * High ratio (>= 0.99) + low cash (< 0.01) = pattern risk (Rule 86B)
     */
    private BigDecimal itcUtilizationRatio = BigDecimal.ZERO;

    /**
     * ITC_TO_TAX_RATIO = eligibleItc / totalOutputTax
     * High ratio (>= 5.0) = inverted duty indicator or ITC accumulation
     */
    private BigDecimal itcToTaxRatio = BigDecimal.ZERO;

    /**
     * CASH_PAYMENT_RATIO = cashTaxPaid / totalOutputTax
     * Low ratio (< 0.01) combined with high ITC ratio = credit-driven discharge
     */
    private BigDecimal cashPaymentRatio = BigDecimal.ZERO;

    /**
     * ITC_PAYMENT_RATIO = itcPaymentTotal / totalOutputTax
     * Share of liability discharged via ITC (complementary to cash ratio)
     */
    private BigDecimal itcPaymentRatio = BigDecimal.ZERO;

    /**
     * RCM_TO_TAX_RATIO = rcmTotalTax / totalOutputTax
     * Normally low (< 0.05); high value may indicate inverted duty structure
     */
    private BigDecimal rcmToTaxRatio = BigDecimal.ZERO;

    /**
     * RCM_ITC_RATIO = isrcItc / rcmTotalTax
     * Per Sec 16(4): ISRC ITC available only after RCM tax is paid.
     * Ratio > 1.5 = disproportionate ITC claim on RCM supplies
     */
    private BigDecimal rcmItcRatio = BigDecimal.ZERO;

    /**
     * RCM_CASH_RATIO = rcmPaymentTotal / rcmTotalTax
     * Per Sec 49(4): RCM tax must be cash-discharged.
     * Ratio < 1.0 = RCM not fully paid (risk flag)
     */
    private BigDecimal rcmCashRatio = BigDecimal.ZERO;

    /* =========================================================
       SECTION 26: QUESTIONNAIRE FLAGS (Table 3D)
       
       Q1-Q7 represent regulatory compliance indicators:
       - Q1: NIL return or scheme applicability
       - Q2: Amendments to registration details
       - Q3: Inter-state supplies to unregistered persons
       - Q4: Nil or negative liability
       - Q5: Claims under IGST Act
       - Q6: Relief under Sec 35 or other provisions
       - Q7: (varies by state/authority)
       
       All typically "Y" or "N" per GSTN form.
       ========================================================= */

    private String q1;
    private String q2;
    private String q3;
    private String q4;
    private String q5;
    private String q6;
    private String q7;

    /* =========================================================
       SECTION 27: AI MODEL SCORING & PREDICTIONS
       
       Features computed from financial data and used as inputs
       to machine learning models for risk stratification.
       ========================================================= */

    /**
     * XGBoost risk prediction score (0-1 scale).
     * Trained on historical audit outcomes to predict compliance risk.
     * Threshold: >= 0.70 flags for review (per service config).
     */
    private BigDecimal xgbRiskScore;

    /**
     * DL4J (DeepLearning4J) anomaly score (0-1 scale).
     * Detects statistical anomalies in return pattern vs. historical baseline.
     * Threshold: >= 0.70 flags as anomalous (per service config).
     */
    private BigDecimal dl4jAnomalyScore;

    /**
     * Predicted output tax (feature engineering for XGBoost).
     * Used to detect possible under/over-reporting of liability.
     */
    private BigDecimal predictedOutputTax;

    /**
     * Default risk label (0.0 or 1.0) from historical audit data.
     * Used for model training; 1.0 = high risk / defaulted in audit.
     */
    private float defaultLabel;

    /* =========================================================
       METHOD: Calculate Derived Ratios
       ========================================================= */

    /**
     * Compute feature ratios in [0.0, 1.0] scale before passing bean
     * to AI models. Called AFTER all financial fields are populated.
     *
     * BUG FIX (v1.1): Enhanced to handle edge cases:
     *   - Zero denominator = 0 (not NaN)
     *   - 100% exempt business (totalTurnover=0, nilExempt>0) = ratio 1.0
     *   - Capped at 9.9999 per SQL schema
     */
    public void calculateDerivedRatios() {
        if (eligibleItc != null && eligibleItc.compareTo(BigDecimal.ZERO) > 0) {
            this.itcUtilizationRatio = utilizedItc.divide(eligibleItc, 4, RoundingMode.HALF_UP);
        } else {
            this.itcUtilizationRatio = BigDecimal.ZERO;
        }

        if (totalOutputTax != null && totalOutputTax.compareTo(BigDecimal.ZERO) > 0) {
            this.itcToTaxRatio = netItcTotal.divide(totalOutputTax, 4, RoundingMode.HALF_UP);
            this.cashPaymentRatio = cashTaxPaid.divide(totalOutputTax, 4, RoundingMode.HALF_UP);
            this.itcPaymentRatio = itcPaymentTotal.divide(totalOutputTax, 4, RoundingMode.HALF_UP);
            this.rcmToTaxRatio = rcmTotalTax.divide(totalOutputTax, 4, RoundingMode.HALF_UP);
        } else {
            this.itcToTaxRatio = BigDecimal.ZERO;
            this.cashPaymentRatio = BigDecimal.ZERO;
            this.itcPaymentRatio = BigDecimal.ZERO;
            this.rcmToTaxRatio = BigDecimal.ZERO;
        }

        // FIX: Handle edge case where entire business is nil/exempt
        BigDecimal totalTurnover = (taxableValue != null ? taxableValue : BigDecimal.ZERO)
                .add(nilExemptValue != null ? nilExemptValue : BigDecimal.ZERO);

        if (totalTurnover.compareTo(BigDecimal.ZERO) > 0) {
            this.nilSupplyRatio = (nilExemptValue != null ? nilExemptValue : BigDecimal.ZERO)
                    .divide(totalTurnover, 4, RoundingMode.HALF_UP);
        } else {
            // If no turnover but nil/exempt value exists, it's 100% exempt
            if (nilExemptValue != null && nilExemptValue.compareTo(BigDecimal.ZERO) > 0) {
                this.nilSupplyRatio = new BigDecimal("1.0000");
            } else {
                this.nilSupplyRatio = BigDecimal.ZERO;
            }
        }

        // RCM ratios
        if (rcmTotalTax != null && rcmTotalTax.compareTo(BigDecimal.ZERO) > 0) {
            this.rcmCashRatio = (rcmPaymentTotal != null ? rcmPaymentTotal : BigDecimal.ZERO)
                    .divide(rcmTotalTax, 4, RoundingMode.HALF_UP);

            BigDecimal isrcItc = (itcIsrcIgst != null ? itcIsrcIgst : BigDecimal.ZERO)
                    .add(itcIsrcCgst != null ? itcIsrcCgst : BigDecimal.ZERO)
                    .add(itcIsrcSgst != null ? itcIsrcSgst : BigDecimal.ZERO)
                    .add(itcIsrcCess != null ? itcIsrcCess : BigDecimal.ZERO);

            this.rcmItcRatio = isrcItc.divide(rcmTotalTax, 4, RoundingMode.HALF_UP);
        } else {
            this.rcmCashRatio = BigDecimal.ZERO;
            this.rcmItcRatio = BigDecimal.ZERO;
        }

        // Cap all ratios at 9.9999 per SQL schema
        this.nilSupplyRatio = capRatio(this.nilSupplyRatio);
        this.itcUtilizationRatio = capRatio(this.itcUtilizationRatio);
        this.itcToTaxRatio = capRatio(this.itcToTaxRatio);
        this.cashPaymentRatio = capRatio(this.cashPaymentRatio);
        this.itcPaymentRatio = capRatio(this.itcPaymentRatio);
        this.rcmToTaxRatio = capRatio(this.rcmToTaxRatio);
        this.rcmItcRatio = capRatio(this.rcmItcRatio);
        this.rcmCashRatio = capRatio(this.rcmCashRatio);
    }

    /**
     * Helper: Cap ratio at 9.9999 per SQL schema (MAX_RATIO_CAP).
     * Also floor negative ratios to 0.
     */
    private BigDecimal capRatio(BigDecimal ratio) {
        if (ratio == null) return BigDecimal.ZERO;
        if (ratio.compareTo(BigDecimal.ZERO) < 0) return BigDecimal.ZERO;

        BigDecimal cap = new BigDecimal("9.9999");
        if (ratio.compareTo(cap) > 0) return cap;

        return ratio.setScale(4, RoundingMode.HALF_UP);
    }

    /* =========================================================
       METHOD: Utility - Get NET AVAILABLE ITC
       ========================================================= */

    /**
     * Compute net available ITC after reversals.
     * Per GST Act: NET_AVAILABLE = ELIGIBLE - REVERSED
     * Used by risk assessment service to detect excess ITC.
     */
    public BigDecimal getNetAvailableItc() {
        return (this.eligibleItc != null ? this.eligibleItc : BigDecimal.ZERO)
                .subtract(this.reversedItc != null ? this.reversedItc : BigDecimal.ZERO);
    }

    /**
     * Check if ITC was utilized in excess of available amount.
     * Risk flag: EXCESS_ITC > 0 per Sec 16/17/41/42.
     */
    public boolean hasExcessItc() {
        return this.excessItc != null && this.excessItc.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Check if RCM tax was reported but not cash-discharged.
     * Risk flag: RCM_TAX > 0 AND RCM_PAYMENT = 0 per Sec 49(4).
     */
    public boolean hasRcmDefaulted() {
        return (this.rcmTotalTax != null && this.rcmTotalTax.compareTo(BigDecimal.ZERO) > 0)
                && (this.rcmPaymentTotal == null || this.rcmPaymentTotal.compareTo(BigDecimal.ZERO) == 0);
    }

    /**
     * Check if interest is likely underpaid.
     * Risk flag: calculateInterest > interestPaid per Sec 50.
     */
    public boolean hasUnderpaidInterest() {
        BigDecimal calc = this.calculatedInterest != null ? this.calculatedInterest : BigDecimal.ZERO;
        BigDecimal paid = this.interestPaid != null ? this.interestPaid : BigDecimal.ZERO;
        return calc.compareTo(paid) > 0;
    }

    /**
     * Check if late fee is likely underpaid.
     * Risk flag: calculatedLateFee > lateFeePaid per Sec 47.
     */
    public boolean hasUnderpaidLateFee() {
        BigDecimal calc = this.calculatedLateFee != null ? this.calculatedLateFee : BigDecimal.ZERO;
        BigDecimal paid = this.lateFeePaid != null ? this.lateFeePaid : BigDecimal.ZERO;
        return calc.compareTo(paid) > 0;
    }
}
