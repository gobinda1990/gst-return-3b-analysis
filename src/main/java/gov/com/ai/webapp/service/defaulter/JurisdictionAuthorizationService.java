package gov.com.ai.webapp.service.defaulter;

import com.fasterxml.jackson.databind.JsonNode;
import gov.com.ai.webapp.exception.DefaulterException;
import gov.com.ai.webapp.repository.CommonUserRepo;
import gov.com.ai.webapp.repository.GstJurisdictionRepository;
import gov.com.ai.webapp.repository.GstJurisdictionRepository.OfficerJurisdiction;
import gov.com.ai.webapp.repository.GstJurisdictionRepository.TaxpayerJurisdiction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Resolves the offices an officer is assigned to and enforces that a taxpayer
 * (GSTIN) falls under one of those offices before any statutory action.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JurisdictionAuthorizationService {

	private final GstJurisdictionRepository jurisdictionRepository;
	
	private final CommonUserRepo commonUserRepo;

	/**
	 * Office IDs assigned to the officer. Never null; empty when the officer has no
	 * assignment.
	 */
	public List<String> resolveOfficeIds(String hrmsCode) {

		if (hrmsCode == null || hrmsCode.isBlank()) {
			return List.of();
		}

		List<JsonNode> assignments = commonUserRepo.fetchAssignedOffices(hrmsCode.trim());

		if (assignments == null || assignments.isEmpty()) {
			return List.of();
		}

		return assignments.stream().filter(Objects::nonNull).flatMap(node -> {
			JsonNode offices = node.path("offices");
			return offices.isArray() ? StreamSupport.stream(offices.spliterator(), false) : Stream.<JsonNode>empty();
		}).map(office -> office.path("officeId").asText("").trim()).filter(id -> !id.isBlank()).distinct().toList();
	}

	/**
	 * @throws DefaulterException    when no jurisdiction exists for the GSTIN
	 * @throws AccessDeniedException when the taxpayer's office/jurisdiction is not among the
	 *                                officer's assigned offices or mapped jurisdictions
	 */
	public TaxpayerJurisdiction requireAccess(List<String> officeIds, String gstin) {

		TaxpayerJurisdiction jurisdiction = jurisdictionRepository.find(gstin)
				.orElseThrow(() -> new DefaulterException("Jurisdiction not found for GSTIN " + gstin));

		String taxpayerOffice = jurisdiction.officeCode() == null ? null : jurisdiction.officeCode().trim();
		String taxpayerStJuri = jurisdiction.stJuri() == null ? null : jurisdiction.stJuri().trim();

		if (officeIds == null || officeIds.isEmpty() || taxpayerOffice == null || taxpayerOffice.isEmpty()) {
			log.warn("Jurisdiction access denied due to missing assignments or taxpayer office gstin={}", gstin);
			throw new AccessDeniedException("Officer is not authorized for the jurisdiction of GSTIN " + gstin);
		}

		// 1. Direct match check against standard office codes
		boolean hasDirectMatch = officeIds.contains(taxpayerOffice);

		// 2. Database-backed mapping check via officerJuri lookup using the assigned office IDs
		boolean hasMappedMatch = false;
		if (!hasDirectMatch) {
			List<OfficerJurisdiction> mappedJurisdictions = jurisdictionRepository.officerJuri(officeIds);
			
			hasMappedMatch = mappedJurisdictions.stream()
					.anyMatch(j -> 
						(j.jurisdictionCode() != null && j.jurisdictionCode().trim().equalsIgnoreCase(taxpayerStJuri)) ||
						(j.mergedJuriCode() != null && j.mergedJuriCode().trim().equalsIgnoreCase(taxpayerOffice))
					);
		}

		if (!hasDirectMatch && !hasMappedMatch) {
			log.warn("Jurisdiction access denied gstin={} taxpayerOffice={} taxpayerStJuri={}", gstin, taxpayerOffice, taxpayerStJuri);
			throw new AccessDeniedException("Officer is not authorized for the jurisdiction of GSTIN " + gstin);
		}

		return jurisdiction;
	}
}