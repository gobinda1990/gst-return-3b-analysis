package gov.com.ai.webapp.model.dto;


public record GstCompliance(

        int totalReturns,

        int delayedReturns,

        int onTimeReturns,

        int missingFilingDates,

        int maximumDelayDays,

        double onTimePercentage,

        double averageDelayDays,

        boolean lateFeeApplicable,

        boolean interestApplicable
) {
}
