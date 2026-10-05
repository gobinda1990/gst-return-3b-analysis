package gov.com.ai.webapp.scheduler;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import gov.com.ai.webapp.service.defaulter.Section62EligibilityService;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaulterStatutoryScheduler {
    private final Section62EligibilityService section62EligibilityService;

    @Scheduled(
        cron = "${gst.defaulter.section62-cron:0 15 2 * * *}",
        zone = "Asia/Kolkata"
    )
    public void section62Scan() {
        try {
            var result = section62EligibilityService.scan();
            log.info("Section 62 scan completed: {}", result);
        } catch (Exception ex) {
            log.error("Section 62 scan failed", ex);
        }
    }
}
