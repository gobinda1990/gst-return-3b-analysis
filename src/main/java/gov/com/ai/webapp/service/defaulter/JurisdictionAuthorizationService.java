package gov.com.ai.webapp.service.defaulter;

import gov.com.ai.webapp.exception.DefaulterException;
import gov.com.ai.webapp.exception.JurisdictionAccessDeniedException;
import gov.com.ai.webapp.repository.GstJurisdictionRepository;
import gov.com.ai.webapp.repository.GstJurisdictionRepository.TaxpayerJurisdiction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JurisdictionAuthorizationService {
    private final GstJurisdictionRepository jurisdictionRepository;
    private final OfficerAssignmentService assignmentService;

    public TaxpayerJurisdiction requireAccess(String officer, String gstin) {
        TaxpayerJurisdiction jurisdiction = jurisdictionRepository.find(gstin)
            .orElseThrow(() -> new DefaulterException(
                "Jurisdiction not found for GSTIN " + gstin));

//        if (!assignmentService.hasOfficeAccess(officer, jurisdiction.officeCode())) {
//            throw new JurisdictionAccessDeniedException("Officer is not authorized for taxpayer jurisdiction");
//        }
        return jurisdiction;
    }
}
