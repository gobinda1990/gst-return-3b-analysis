package gov.com.ai.webapp.model.revenue; import java.math.BigDecimal;
public record OfficeRevenueDashboardResponse(long totalOffices,long filedGstins,BigDecimal taxableValue,BigDecimal outputTax,BigDecimal igst,BigDecimal cgst,BigDecimal sgst,BigDecimal cess,BigDecimal eligibleItc,BigDecimal utilizedItc,BigDecimal cashTaxPaid,BigDecimal avgMomGrowth,BigDecimal avgYoyGrowth) {}
