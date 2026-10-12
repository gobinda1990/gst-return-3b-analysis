package gov.com.ai.webapp.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class GstJurisdictionRepository {

	private final JdbcTemplate jdbcTemplate;
	
	// Lazy-initialize or inject NamedParameterJdbcTemplate using the existing JdbcTemplate
	private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

	private final String JURI_QUERY = "SELECT a.st_juri, b.merged_jurisdiction AS office_code, "
			+ "b.charge_cd_vat AS office_name FROM GST_DEALER_MASTER_WBCOMTAX a "
			+ "JOIN gst_master_juri_new b ON a.st_juri = b.jurisdiction_code "
			+ "WHERE a.gstin = ? AND a.auth_status = 'A'";
	
	// Notice the clean :offices named parameter syntax for lists
	private final String JURI_QUERY_OFFICER = "SELECT JURISDICTION_CODE, merged_juri_code FROM gst_master_juri_new WHERE charge_cd_vat IN (:offices)";

	private NamedParameterJdbcTemplate getNamedParameterJdbcTemplate() {
		if (this.namedParameterJdbcTemplate == null) {
			this.namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
		}
		return this.namedParameterJdbcTemplate;
	}

	public Optional<TaxpayerJurisdiction> find(String gstin) {
		return jdbcTemplate
				.query(JURI_QUERY,
						rs -> rs.next()
								? Optional.of(new TaxpayerJurisdiction(rs.getString("st_juri"),
										rs.getString("office_code"), rs.getString("office_name")))
								: Optional.empty(),
						gstin);
	}
	
	public List<OfficerJurisdiction> officerJuri(List<String> offices) {
		if (offices == null || offices.isEmpty()) {
			return Collections.emptyList();
		}

		Map<String, Object> params = Map.of("offices", offices);

		return getNamedParameterJdbcTemplate().query(
				JURI_QUERY_OFFICER,
				params,
				(rs, rowNum) -> new OfficerJurisdiction(
						rs.getString("JURISDICTION_CODE"),
						rs.getString("merged_juri_code")
				)
		);
	}

	public record TaxpayerJurisdiction(String stJuri, String officeCode, String officeName) {
	}

	public record OfficerJurisdiction(String jurisdictionCode, String mergedJuriCode) {
	}
}