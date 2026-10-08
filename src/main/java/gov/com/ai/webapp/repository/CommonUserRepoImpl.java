package gov.com.ai.webapp.repository;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Repository implementation for common user operations.
 * 
 * Features: - Query optimization with caching - Comprehensive error handling -
 * Transactional support - Bulk operations support - Null safety checks -
 * Logging and monitoring
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class CommonUserRepoImpl implements CommonUserRepo {
	
	private final JdbcTemplate jdbcTemplate;	
	
	private static final String ACTIVE_STATUS = "A";
	
	private final ObjectMapper objectMapper=new ObjectMapper();
	
	public  final String SQL_FETCH_USER_OFFICES =" SELECT jsonb_build_object('hrmsCode', upd.hrms_code,'officeType', "
			+ " posting_item->>'officeType','offices', jsonb_agg(jsonb_build_object('officeId', office_item->>'officeId', "
			+ " 'officeName', office_item->>'officeName') ORDER BY office_item->>'officeName') "
			+ " ) AS user_offices FROM acs_mast.user_assign_det upd "
			+ " CROSS JOIN LATERAL jsonb_array_elements(upd.postings) AS posting_item "
			+ " LEFT JOIN LATERAL jsonb_array_elements(posting_item->'offices') AS office_item ON TRUE "
			+ " WHERE upd.status = ? "
			+ " AND upd.hrms_code = ? "
			+ " AND posting_item->>'postingType' = 'M' "
			+ " AND posting_item->>'status' = ? "
			+ " AND office_item->>'status' =? "
			+ " AND EXISTS ( SELECT 1 FROM jsonb_array_elements(posting_item->'modules') AS module_item "
			+ " WHERE module_item->>'projectId' = '1' ) "
			+ " GROUP BY upd.hrms_code, posting_item->>'officeType'";

	@Override
	@Transactional(readOnly = true)
//	@Cacheable(value = "userOffices", key = "#hrmsCode", unless = "#result == null || #result.isEmpty()")
	public List<JsonNode> fetchAssignedOffices(String hrmsCode) {
		String methodName = "fetchAssignedOffices";
		log.debug("[{}] Fetching assigned offices for HRMS: {}", methodName, hrmsCode);

		try {
			if (hrmsCode == null || hrmsCode.isBlank()) {
				log.warn("[{}] HRMS code is empty", methodName);
				return Collections.emptyList();
			}

			List<JsonNode> offices = jdbcTemplate.query(SQL_FETCH_USER_OFFICES, ps -> {
				ps.setString(1, ACTIVE_STATUS);
				ps.setString(2, hrmsCode);
				ps.setString(3, ACTIVE_STATUS);
				ps.setString(4, ACTIVE_STATUS);
			}, (rs, rowNum) -> {
				String json = rs.getString("user_offices");
				if (json == null || json.isBlank()) {
					log.debug("[{}] Empty JSON for HRMS: {}", methodName, hrmsCode);
					return null;
				}

				try {
					return objectMapper.readTree(json);
				} catch (JsonProcessingException jpe) {
					log.error("[{}] JSON parsing error for HRMS: {} | Error: {}", methodName, hrmsCode,
							jpe.getMessage(), jpe);
					throw new RuntimeException("Failed to parse office data for HRMS: " + hrmsCode,jpe);
				}
			}).stream().filter(Objects::nonNull).collect(Collectors.toList());

		//	log.info("[{}] Retrieved {} offices for HRMS: {}", methodName, offices.size(), hrmsCode);
			return offices;

		} catch (DataAccessException dae) {
			log.error("[{}] Database error fetching offices for HRMS: {} | Error: {}", methodName, hrmsCode,
					dae.getMessage(), dae);
			throw new RuntimeException("Failed to fetch assigned offices for HRMS: " + hrmsCode);
		}
	}
	

	
}
