package com.udapay.ledger.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Maps Keycloak realm roles to Spring Security authorities.
 *
 * <p>Keycloak places realm roles in the token as
 * <pre>{"realm_access": {"roles": ["reader", "writer"]}}</pre>
 * Spring's {@code hasRole('writer')} expects an authority named
 * {@code ROLE_writer}, so each role is prefixed accordingly.
 */
public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    public static final String REALM_ACCESS_CLAIM = "realm_access";
    public static final String ROLES_KEY = "roles";
    public static final String ROLE_PREFIX = "ROLE_";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS_CLAIM);
        if (realmAccess == null) {
            return List.of();
        }
        Object roles = realmAccess.get(ROLES_KEY);
        if (!(roles instanceof Collection<?> roleList)) {
            return List.of();
        }
        return roleList.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(role -> !role.isBlank())
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(ROLE_PREFIX + role))
                .toList();
    }
}
