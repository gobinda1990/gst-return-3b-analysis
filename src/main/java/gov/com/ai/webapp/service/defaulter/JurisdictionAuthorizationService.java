package gov.com.ai.webapp.service.defaulter;

import com.fasterxml.jackson.databind.JsonNode;
import gov.com.ai.webapp.exception.DefaulterException;
import gov.com.ai.webapp.repository.CommonUserRepo;
import gov.com.ai.webapp.repository.GstJurisdictionRepository;
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
	 * @throws AccessDeniedException when the taxpayer's office is not among the
	 *                               officer's assigned offices
	 */
	public TaxpayerJurisdiction requireAccess(List<String> officeIds, String gstin) {

		TaxpayerJurisdiction jurisdiction = jurisdictionRepository.find(gstin)
				.orElseThrow(() -> new DefaulterException("Jurisdiction not found for GSTIN " + gstin));

		String taxpayerOffice = jurisdiction.officeCode() == null ? null : jurisdiction.officeCode().trim();

		if (officeIds == null || officeIds.isEmpty() || taxpayerOffice == null || taxpayerOffice.isEmpty()
				|| !officeIds.contains(taxpayerOffice)) {

			log.warn("Jurisdiction access denied gstin={} taxpayerOffice={}", gstin, taxpayerOffice);

			throw new AccessDeniedException("Officer is not authorized for the jurisdiction of GSTIN " + gstin);
		}

		return jurisdiction;
	}
}