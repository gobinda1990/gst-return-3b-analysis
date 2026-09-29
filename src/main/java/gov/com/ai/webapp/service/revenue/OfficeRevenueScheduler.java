package gov.com.ai.webapp.service.revenue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import gov.com.ai.webapp.config.RevenueProperties;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

@Slf4j
@Component
@RequiredArgsConstructor
public class OfficeRevenueScheduler {
	private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("MMuuuu");
	private final OfficeRevenueBatchService service;
	private final RevenueProperties props;

	@Scheduled(cron = "${gst.revenue.scheduler-cron:0 30 2 * * *}")
	public void run() {
		if (!props.isSchedulerEnabled())
			return;
		String p = YearMonth.now().minusMonths(1).format(F);
		try {
			service.run(p);
		} catch (Exception e) {
			log.error("Scheduled office revenue batch failed period={}", p, e);
		}
	}
}
