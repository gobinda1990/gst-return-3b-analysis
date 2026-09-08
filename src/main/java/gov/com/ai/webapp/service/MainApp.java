//package gov.com.ai.webapp.service;
//
//
//import java.time.LocalDate;
//
//import java.util.List;
//
//public class MainApp {
//    public static void main(String[] args) {
//        // 1. Fetch Dynamic Dropdown List
//        System.out.println("=== DYNAMIC FY DROPDOWN LIST (Current Date -> 2017-18) ===");
//        List<String> fyOptions = GSTFinancialYearService.getFinancialYearDropdownList();
//        fyOptions.forEach(fy -> System.out.println("Option: FY " + fy));
//
//        // 2. Expand Dropdown Selection into 12 Return Periods (MMYYYY)
//        String selectedOption = "26-27";
//        System.out.println("\n=== RETURN PERIODS FOR SELECTED FY: " + selectedOption + " ===");
//        List<String> returnPeriods = GSTFinancialYearService.getReturnPeriodsForFY(selectedOption);
//        returnPeriods.forEach(period -> System.out.println("Period: " + period));
//
//        // 3. Resolve FY from a Single MMYYYY String
//        String inputPeriod = "072017";
//        System.out.println("\n=== RESOLVE FY FROM SINGLE PERIOD ===");
//        System.out.println("Period: " + inputPeriod + " -> FY: " + GSTFinancialYearService.getFinancialYearForPeriod(inputPeriod));
//    }
//}
