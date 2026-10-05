package gov.com.ai.webapp.service.defaulter;

import gov.com.ai.webapp.repository.Gstr3bFilingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class Gstr3aEligibilityService {
    private final ReturnFilingEligibilityService requirementService;
    private final GstDueDateService dueDateService;
    private final Gstr3bFilingRepository filingRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public EligibilityResult evaluate(String gstin, String period) {
        var requirement = requirementService.determine(gstin, period);
        if (!requirement.required()) return EligibilityResult.no("RETURN_NOT_REQUIRED");

        LocalDate dueDate = dueDateService.resolve(
            period, requirement.frequency(), requirement.category());

        if (!LocalDate.now(clock).isAfter(dueDate)) {
            return EligibilityResult.no("RETURN_NOT_YET_DUE");
        }
        if (filingRepository.hasFiled(gstin, period)) {
            return EligibilityResult.no("RETURN_ALREADY_FILED");
        }

        return new EligibilityResult(
            true, null, dueDate, requirement.frequency(), requirement.category());
    }

    public record EligibilityResult(
            boolean eligible, String reason, LocalDate dueDate,
            String frequency, String category) {
        public static EligibilityResult no(String reason) {
            return new EligibilityResult(false, reason, null, null, null);
        }
    }
}
