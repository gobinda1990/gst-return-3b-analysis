package gov.com.ai.webapp.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Robust, production-ready utility service for GST Financial Year and Return
 * Period (MMYYYY) calculations.
 */
@Service
@RequiredArgsConstructor
public final class GSTFinancialYearService {

	public static final int GST_INCEPTION_YEAR = 2017; // GST launched July 2017 (FY 17-18)
	private static final DateTimeFormatter MMYYYY_FORMATTER = DateTimeFormatter.ofPattern("MMyyyy");

	/**
	 * Generates a list of Financial Years from current date back to 17-18.
	 * Thread-safe and stateless.
	 *
	 * @return List of FY strings in "YY-YY" format (e.g., ["26-27", "25-26", ...,
	 *         "17-18"])
	 */
	public List<String> getFinancialYearDropdownList() {
		return getFinancialYearDropdownList(LocalDate.now());
	}

	/**
	 * Overload for deterministic testing with a reference date.
	 */
	public  List<String> getFinancialYearDropdownList(LocalDate referenceDate) {
		Objects.requireNonNull(referenceDate, "Reference date cannot be null");

		List<String> fyList = new ArrayList<>();
		int currentYear = referenceDate.getYear();
		int currentMonth = referenceDate.getMonthValue();

		// Calculate current FY start year (April or later -> current year; Jan-Mar ->
		// previous year)
		int currentFYStartYear = (currentMonth >= 4) ? currentYear : currentYear - 1;

		// Populate backwards to GST launch year (2017)
		for (int startYear = currentFYStartYear; startYear >= GST_INCEPTION_YEAR; startYear--) {
			fyList.add(formatFinancialYear(startYear));
		}

		return Collections.unmodifiableList(fyList);
	}

	/**
	 * Expands a selected FY string (e.g. "26-27" or "2026-2027") into 12 return
	 * periods ("MMYYYY").
	 *
	 * @param selectedFY String formatted as "YY-YY" or "YYYY-YYYY"
	 * @return List of 12 MMYYYY strings from April (04YYYY) to March (03YYYY+1)
	 * @throws IllegalArgumentException if the format is invalid or prior to GST
	 *                                  launch
	 */
	public  List<String> getReturnPeriodsForFY(String selectedFY) {
		if (selectedFY == null || !selectedFY.contains("-")) {
			throw new IllegalArgumentException(
					"Invalid FY format. Expected format like '26-27' or '2026-2027'. Received: " + selectedFY);
		}

		String[] parts = selectedFY.split("-");
		if (parts.length != 2) {
			throw new IllegalArgumentException("Invalid FY structure: " + selectedFY);
		}

		int startYear;
		try {
			startYear = Integer.parseInt(parts[0].trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid year component in FY: " + selectedFY, e);
		}

		// Standardize 2-digit year to 4-digit year (e.g., 26 -> 2026)
		if (startYear < 100) {
			startYear += 2000;
		}

		if (startYear < GST_INCEPTION_YEAR) {
			throw new IllegalArgumentException(
					"Financial year cannot be prior to GST launch year (2017-18). Provided: " + selectedFY);
		}

		List<String> returnPeriods = new ArrayList<>(12);
		int nextYear = startYear + 1;

		// April (04) to December (12) of start year
		for (int month = 4; month <= 12; month++) {
			returnPeriods.add(YearMonth.of(startYear, month).format(MMYYYY_FORMATTER));
		}

		// January (01) to March (03) of next year
		for (int month = 1; month <= 3; month++) {
			returnPeriods.add(YearMonth.of(nextYear, month).format(MMYYYY_FORMATTER));
		}

		return Collections.unmodifiableList(returnPeriods);
	}

	/**
	 * Determines the Financial Year string for any single MMYYYY input.
	 *
	 * @param mmyyyy Date string formatted as "MMYYYY" (e.g., "072017")
	 * @return Formatted FY string "YY-YY"
	 */
	public static String getFinancialYearForPeriod(String mmyyyy) {
		if (mmyyyy == null || mmyyyy.length() != 6) {
			throw new IllegalArgumentException("Invalid MMYYYY string length. Expected 6 characters (e.g. '042026').");
		}

		YearMonth ym = YearMonth.parse(mmyyyy, MMYYYY_FORMATTER);
		int month = ym.getMonthValue();
		int year = ym.getYear();

		int fyStartYear = (month >= 4) ? year : year - 1;
		return formatFinancialYear(fyStartYear);
	}

	private static String formatFinancialYear(int startYear) {
		int startYY = startYear % 100;
		int endYY = (startYear + 1) % 100;
		return String.format("%02d-%02d", startYY, endYY);
	}
}
