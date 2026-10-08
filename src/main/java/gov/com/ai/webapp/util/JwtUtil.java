package gov.com.ai.webapp.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.stream.Collectors;

@Component
@Slf4j
public class JwtUtil {

	public String getHrmsCode(Jwt jwt) {
		if (jwt == null)
			return null;
		try {
			return jwt.getSubject();
		} catch (Exception e) {
			log.error("Error extracting HRMS code from JWT", e);
			return null;
		}
	}

	public String getFullName(Jwt jwt) {
		if (jwt == null)
			return null;
		try {
			Object claim = jwt.getClaim("fullName");
			return claim != null ? claim.toString() : null;
		} catch (Exception e) {
			log.error("Error extracting full name from JWT", e);
			return null;
		}
	}

	public List<String> extractRoles(Jwt jwt) {
		if (jwt == null)
			return List.of();
		try {
			List<String> roles = jwt.getClaimAsStringList("roles");
			if (roles == null)
				return List.of();

			return roles.stream().map(role -> role.replaceFirst("^ROLE_", "")).collect(Collectors.toList());
		} catch (Exception e) {
			log.error("Error extracting roles from JWT", e);
			return List.of();
		}
	}

	public String extractPrimaryRole(Jwt jwt) {
		List<String> roles = extractRoles(jwt);
		return roles.isEmpty() ? null : roles.get(0);
	}
}