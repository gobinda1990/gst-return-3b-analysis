package gov.com.ai.webapp.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class GstJurisdictionRepository {

	private final JdbcTemplate jdbcTemplate;

	private final String JURI_QUERY = " SELECT  a.st_juri,b.merged_jurisdiction AS office_code, "
			+ " b.charge_cd_vat AS office_name FROM GST_DEALER_MASTER_WBCOMTAX a "
			+ "	JOIN gst_master_juri_new b ON a.st_juri = b.jurisdiction_code "
			+ "	WHERE a.gstin = ? AND a.auth_status = 'A' ";

	public Optional<TaxpayerJurisdiction> find(String gstin) {
		return jdbcTemplate
				.query(JURI_QUERY,
						rs -> rs.next()
								? Optional.of(new TaxpayerJurisdiction(rs.getString("st_juri"),
										rs.getString("office_code"), rs.getString("office_name")))
								: Optional.empty(),
						gstin);
	}

	public record TaxpayerJurisdiction(String stJuri, String officeCode, String officeName) {
	}
}