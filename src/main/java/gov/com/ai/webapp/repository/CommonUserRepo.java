package gov.com.ai.webapp.repository;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;

public interface CommonUserRepo {	
	
    List<JsonNode> fetchAssignedOffices(String hrmsCode) ; 

}
