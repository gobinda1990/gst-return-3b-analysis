package gov.com.ai.webapp.config;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.NonNull;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import gov.com.ai.webapp.model.CustomUserDetails;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class CustomJwtConverter implements Converter<Jwt, CustomUserDetails> {

	@Override
	public CustomUserDetails convert(@NonNull Jwt jwt) {		

		// --- Extract roles from multiple potential claim sources ---
		List<String> roles = extractRoles(jwt);

		// --- Map to GrantedAuthorities ---
		Collection<GrantedAuthority> authorities = roles.stream().filter(Objects::nonNull).distinct()
				.map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r.toUpperCase(Locale.ROOT))
				.map(SimpleGrantedAuthority::new).collect(Collectors.toList());

		// --- Build user details safely ---
		CustomUserDetails user = new CustomUserDetails();
		user.setHrmsCode(getClaim(jwt, "hrmsCd", jwt.getSubject()));
		user.setFullName(getClaim(jwt,"fullName"));
		user.setCircleCd(getClaim(jwt, "circleCd"));
		user.setChargeCd(getClaim(jwt, "chargeCd"));
		user.setEmail(getClaim(jwt, "emailId"));
		user.setPhoneNo(getClaim(jwt, "phoneNo"));
		user.setGpfNo(getClaim(jwt, "gpfNo"));
		user.setPanNo(getClaim(jwt, "panNo"));
		user.setBoId(getClaim(jwt, "boId"));
		user.setAuthorities(authorities);

		log.debug("Extracted user from JWT: hrmsCd={}, roles={}", user.getHrmsCode(), roles);

		return user;
	}

	/**
	 * Extracts roles from possible claim structures. Supports flat 'roles',
	 * 'authorities', or Keycloak-style 'realm_access' and 'resource_access'.
	 */
	@SuppressWarnings("unchecked")
	private List<String> extractRoles(Jwt jwt) {
		// Try direct claim "roles"
		List<String> roles = jwt.getClaimAsStringList("roles");
		if (roles != null && !roles.isEmpty()) {
			return roles;
		}

		// Try alternate "authorities" claim
		roles = jwt.getClaimAsStringList("authorities");
		if (roles != null && !roles.isEmpty()) {
			return roles;
		}

		// Try Keycloak "realm_access"
		Map<String, Object> realmAccess = jwt.getClaim("realm_access");
		if (realmAccess != null && realmAccess.get("roles") instanceof List<?> realmRoles) {
			return realmRoles.stream().map(Object::toString).collect(Collectors.toList());
		}

		// Try Keycloak "resource_access" (for API-level roles)
		Map<String, Object> resourceAccess = jwt.getClaim("resource_access");
		if (resourceAccess != null) {
			return resourceAccess.values().stream().filter(Map.class::isInstance).map(Map.class::cast)
					.flatMap(m -> ((List<?>) m.getOrDefault("roles", List.of())).stream()).map(Object::toString)
					.collect(Collectors.toList());
		}

		return List.of(); // default empty roles
	}

	/**
	 * Safely extracts a claim with fallback value.
	 */
	private String getClaim(Jwt jwt, String claimName, String... fallback) {
		String value = jwt.getClaimAsString(claimName);
		if (value == null && fallback != null && fallback.length > 0) {
			return fallback[0];
		}
		return value;
	}
}
