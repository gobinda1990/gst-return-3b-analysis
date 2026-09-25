package gov.com.ai.webapp.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GrowthRowResponse {

    private String gstin;
    private String tradeName;

    private String stJuri;
    private String officeName;
    private String retPeriod;
    private LocalDate periodDate;
    private BigDecimal taxableValue;
    private BigDecimal outputTax;
    private BigDecimal eligibleItc;
    private BigDecimal utilizedItc;
    private BigDecimal cashTaxPaid;
    private BigDecimal rcmTotalTax;
    private BigDecimal momTaxableGrowth;
    private BigDecimal momOutputTaxGrowth;
    private BigDecimal momItcGrowth;
    private BigDecimal momCashGrowth;
    private BigDecimal yoyTaxableGrowth;
    private BigDecimal yoyOutputTaxGrowth;
    private BigDecimal yoyItcGrowth;
    private BigDecimal yoyCashGrowth;
    private BigDecimal avg3mTaxable;
    private BigDecimal avg6mTaxable;
    private BigDecimal avg12mTaxable;
    private BigDecimal avg3mOutputTax;
    private BigDecimal avg6mOutputTax;
    private BigDecimal avg12mOutputTax;
    private BigDecimal avg3mItc;
    private BigDecimal avg6mItc;
    private BigDecimal avg12mItc;

    private Integer filingDelayDays;

    private String growthTrend;

    /*
     * Source:
     * GST_3B_RISK_PROFILE
     */
    private BigDecimal riskScore;
    private String riskLevel;
}