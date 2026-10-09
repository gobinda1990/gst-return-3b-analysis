
package gov.com.ai.webapp.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Repository
@Slf4j
public class CommonUserRepoImpl implements CommonUserRepo {

	private static final String ACTIVE_STATUS = "A";
	private static final String MAIN_POSTING = "M";
	private static final String PROJECT_ID = "1";

	private final JdbcTemplate postgresJdbcTemplate;
	private final ObjectMapper objectMapper;

	public CommonUserRepoImpl(@Qualifier("postgresJdbcTemplate") JdbcTemplate postgresJdbcTemplate,
			ObjectMapper objectMapper) {

		this.postgresJdbcTemplate = postgresJdbcTemplate;
		this.objectMapper = objectMapper;
	}

	private static final String SQL_FETCH_USER_OFFICES = """
			SELECT jsonb_build_object(
			    'hrmsCode', upd.hrms_code,
			    'officeType', posting_item.value->>'officeType',
			    'offices',
			        jsonb_agg(
			            jsonb_build_object(
			                'officeId', office_item.value->>'officeId',
			                'officeName', office_item.value->>'officeName'
			            )
			            ORDER BY office_item.value->>'officeName'
			        )
			) AS user_offices

			FROM acs_mast.user_assign_det upd

			CROSS JOIN LATERAL jsonb_array_elements(
			    CASE
			        WHEN jsonb_typeof(upd.postings::jsonb) = 'array'
			        THEN upd.postings::jsonb
			        ELSE '[]'::jsonb
			    END
			) AS posting_item(value)

			CROSS JOIN LATERAL jsonb_array_elements(
			    CASE
			        WHEN jsonb_typeof(
			            posting_item.value->'offices'
			        ) = 'array'
			        THEN posting_item.value->'offices'
			        ELSE '[]'::jsonb
			    END
			) AS office_item(value)

			WHERE upd.status = ?
			  AND upd.hrms_code = ?

			  AND posting_item.value->>'postingType' = ?
			  AND posting_item.value->>'status' = ?

			  AND office_item.value->>'status' = ?

			  AND EXISTS (
			      SELECT 1
			      FROM jsonb_array_elements(
			          CASE
			              WHEN jsonb_typeof(
			                  posting_item.value->'modules'
			              ) = 'array'
			              THEN posting_item.value->'modules'
			              ELSE '[]'::jsonb
			          END
			      ) AS module_item(value)
			      WHERE module_item.value->>'projectId' = ?
			  )

			GROUP BY
			    upd.hrms_code,
			    posting_item.value->>'officeType'
			""";
	
	

	@Override
	@Transactional(transactionManager = "postgresTransactionManager", readOnly = true)
	public List<JsonNode> fetchAssignedOffices(String hrmsCode) {

		if (hrmsCode == null || hrmsCode.isBlank()) {
			log.warn("Invalid HRMS code supplied");
			return List.of();
		}

		String normalizedHrmsCode = hrmsCode.trim();
		try {
			List<JsonNode> offices = postgresJdbcTemplate.query(SQL_FETCH_USER_OFFICES, ps -> {
				ps.setString(1, ACTIVE_STATUS);
				ps.setString(2, normalizedHrmsCode);
				ps.setString(3, MAIN_POSTING);
				ps.setString(4, ACTIVE_STATUS);
				ps.setString(5, ACTIVE_STATUS);
				ps.setString(6, PROJECT_ID);
			}, (rs, rowNum) -> {
				String json = rs.getString("user_offices");
				if (json == null || json.isBlank()) {
					throw new IllegalStateException("Database returned empty user_offices JSON");
				}
				try {
					return objectMapper.readTree(json);
				} catch (JsonProcessingException ex) {
					throw new IllegalStateException("Invalid user_offices JSON returned by database", ex);
				}
			});

			log.info("Fetched {} office groups for HRMS {}", offices.size(), normalizedHrmsCode);
			return offices;

		} catch (DataAccessException ex) {

			log.error("PostgreSQL query failed for HRMS {}", normalizedHrmsCode, ex);

			throw ex;
		}
	}
	
	
	
}
