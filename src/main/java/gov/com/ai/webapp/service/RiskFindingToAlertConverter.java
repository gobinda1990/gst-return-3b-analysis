package gov.com.ai.webapp.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.stereotype.Component;
import gov.com.ai.webapp.model.DashboardDTOs;
import gov.com.ai.webapp.model.Return3BSummaryBean;
import gov.com.ai.webapp.service.GstReturn3bRiskAssessmentService.RiskFinding;

@Component
public class RiskFindingToAlertConverter {

    private static final DateTimeFormatter DATE_FORMATTER = 
        DateTimeFormatter.ofPattern("dd-MMM-yyyy");

    /**
     * Convert RiskFinding to ComplianceAlertDTO
     */
    public DashboardDTOs.ComplianceAlertDTO convertToAlert(
            Return3BSummaryBean summary,
            RiskFinding finding,
            int compositeScore) {

        DashboardDTOs.ComplianceAlertDTO alert = new DashboardDTOs.ComplianceAlertDTO();
        alert.setId(UUID.randomUUID().toString());
        alert.setGstin(summary.getGstin());
        alert.setRetPeriod(summary.getRetPeriod());
        alert.setMessage(finding.message);
        alert.setExcessItc(finding.evidenceValue);
        alert.setSeverity(finding.severity);
        alert.setRiskLevel(mapSeverityToRiskLevel(finding.severity));
        alert.setCompositeScore(compositeScore);
        alert.setFindingCode(finding.code);
        alert.setCategory(finding.category);
        alert.setLegalBasis(finding.legalBasis);
        alert.setEvidenceValue(finding.evidenceValue);
        alert.setFormattedDate(LocalDate.now().format(DATE_FORMATTER));
        alert.setCreatedAt(LocalDateTime.now());

        return alert;
    }

    private String mapSeverityToRiskLevel(String severity) {
        return switch (severity) {
            case "CRITICAL", "HIGH" -> "HIGH";
            case "MEDIUM" -> "MEDIUM";
            default -> "LOW";
        };
    }
}