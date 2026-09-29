package gov.com.ai.webapp.service.revenue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import gov.com.ai.webapp.exception.DashboardRequestException;
import gov.com.ai.webapp.model.revenue.DefaulterDashboardFilter;
import gov.com.ai.webapp.model.revenue.DefaulterDashboardRow;
import gov.com.ai.webapp.model.revenue.DefaulterDashboardSummary;
import gov.com.ai.webapp.model.revenue.OptionDto;
import gov.com.ai.webapp.model.revenue.PageResponse;
import gov.com.ai.webapp.repository.revenue.DefaulterDashboardRepository;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaulterDashboardService {

	private static final Set<String> FILING_STATUSES = Set.of("NOT_DUE", "FILED_ON_TIME", "FILED_LATE", "NOT_FILED");

	private static final Set<String> DEFAULT_LEVELS = Set.of("NORMAL", "WARNING", "HIGH", "CRITICAL");

	private static final Set<String> YES_NO = Set.of("Y", "N");

	private final DefaulterDashboardRepository repository;

	public DefaulterDashboardSummary summary(DefaulterDashboardFilter filter) {

		DefaulterDashboardFilter validated = validate(filter, false);

		long started = System.nanoTime();

		DefaulterDashboardSummary response = repository.summary(validated);

		log.info("Defaulter dashboard summary period={} office={} total={} durationMs={}", validated.retPeriod(),
				validated.office(), response.total(), (System.nanoTime() - started) / 1_000_000);

		return response;
	}

	public PageResponse<DefaulterDashboardRow> page(DefaulterDashboardFilter filter) {

		DefaulterDashboardFilter validated = validate(filter, false);

		long started = System.nanoTime();

		PageResponse<DefaulterDashboardRow> response = repository.page(validated);

		log.info("Defaulter dashboard page period={} office={} page={} size={} rows={} total={} durationMs={}",
				validated.retPeriod(), validated.office(), validated.page(), validated.size(),
				response.content().size(), response.totalElements(), (System.nanoTime() - started) / 1_000_000);

		return response;
	}

	public List<OptionDto> periods() {
		return repository.periods();
	}

	public List<OptionDto> offices(String retPeriod) {

		validatePeriod(retPeriod);

		return repository.offices(retPeriod);
	}

	public void export(DefaulterDashboardFilter filter, Writer writer) throws IOException {

		DefaulterDashboardFilter validated = validate(filter, true);

		log.info("Defaulter dashboard CSV START period={} office={}", validated.retPeriod(), validated.office());

		repository.streamCsv(validated, writer);

		log.info("Defaulter dashboard CSV COMPLETE period={} office={}", validated.retPeriod(), validated.office());
	}

	private DefaulterDashboardFilter validate(DefaulterDashboardFilter filter, boolean export) {

		if (filter == null) {
			throw new DashboardRequestException("Dashboard filter is required");
		}

		validatePeriod(filter.retPeriod());

		int page = export ? 0 : Math.max(filter.page(), 0);

		int size = export ? 100 : Math.max(1, Math.min(filter.size(), 100));

		String filingStatus = upper(filter.filingStatus());

		String riskLevel = upper(filter.riskLevel());

		String defaultLevel = upper(filter.defaultLevel());

		String gstr3aEligible = upper(filter.gstr3aEligible());

		if (filingStatus != null && !FILING_STATUSES.contains(filingStatus)) {

			throw new DashboardRequestException("Invalid filingStatus: " + filingStatus);
		}

		if (defaultLevel != null && !DEFAULT_LEVELS.contains(defaultLevel)) {

			throw new DashboardRequestException("Invalid defaultLevel: " + defaultLevel);
		}

		if (gstr3aEligible != null && !YES_NO.contains(gstr3aEligible)) {

			throw new DashboardRequestException("gstr3aEligible must be Y or N");
		}

		if (riskLevel != null && riskLevel.length() > 30) {

			throw new DashboardRequestException("riskLevel exceeds 30 characters");
		}

		String search = clean(filter.search());

		if (search != null && search.length() > 100) {

			throw new DashboardRequestException("search exceeds 100 characters");
		}

		return new DefaulterDashboardFilter(filter.retPeriod(), clean(filter.office()), filingStatus, riskLevel,
				defaultLevel, gstr3aEligible, search, page, size);
	}

	private void validatePeriod(String retPeriod) {
	    if (retPeriod == null || !retPeriod.matches("(0[1-9]|1[0-2])\\d{4}")) {
	        throw new DashboardRequestException("retPeriod must be MMYYYY");
	    }
	}

	private String clean(String value) {

		if (value == null) {
			return null;
		}

		String v = value.trim();

		return v.isEmpty() ? null : v;
	}

	private String upper(String value) {

		String v = clean(value);

		return v == null ? null : v.toUpperCase(Locale.ROOT);
	}
}
