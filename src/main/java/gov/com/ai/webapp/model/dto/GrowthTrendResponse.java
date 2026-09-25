package gov.com.ai.webapp.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GrowthTrendResponse {

    private String retPeriod;
    private String label;

    private BigDecimal taxableValue;
    private BigDecimal outputTax;
    private BigDecimal eligibleItc;
    private BigDecimal cashTaxPaid;
}