package gov.com.ai.webapp.service;

import org.springframework.stereotype.Service;

import gov.com.ai.webapp.model.GstMonthlySummaryDto;
import gov.com.ai.webapp.repository.GstRet3bSummaryRepository;

import java.util.List;

@Service
public class GstAnalysisService {

	private final GstRet3bSummaryRepository repository;

	public GstAnalysisService(GstRet3bSummaryRepository repository) {
		this.repository = repository;
	}

	public List<GstMonthlySummaryDto> getMonthlyRevenueSummary(List<String> periods) {
		if (periods == null || periods.isEmpty()) {
			// Default target periods (FY 2025-26)
			periods = List.of("042025", "052025", "062025", "072025", "082025", "092025", "102025", "112025", "122025",
					"012026", "022026", "032026");
		}
		return repository.fetchMonthlyRevenueSummary(periods);
	}
}
