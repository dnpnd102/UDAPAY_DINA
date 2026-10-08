package com.udapay.ledger.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercise 3 — realm_access.roles → ROLE_* mapping. */
class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    @Test
    @DisplayName("each realm role becomes a ROLE_-prefixed authority")
    void mapsRealmRoles() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of("reader", "writer"))));

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_reader", "ROLE_writer");
    }

    @Test
    @DisplayName("token without realm_access yields no authorities")
    void noRealmAccessClaim() {
        assertThat(converter.convert(jwt(Map.of("scope", "openid")))).isEmpty();
    }

    @Test
    @DisplayName("realm_access without a roles list yields no authorities")
    void realmAccessWithoutRoles() {
        assertThat(converter.convert(jwt(Map.of("realm_access", Map.of("other", "x"))))).isEmpty();
        assertThat(converter.convert(jwt(Map.of("realm_access", Map.of("roles", "not-a-list"))))).isEmpty();
    }

    @Test
    @DisplayName("blank or null entries are dropped")
    void dropsBlankRoles() {
        java.util.List<String> roles = new java.util.ArrayList<>();
        roles.add("reader");
        roles.add("");
        roles.add(null);
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", roles)));

        assertThat(converter.convert(jwt)).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_reader");
    }

    @Test
    @DisplayName("the SecurityConfig bean uses the converter and preferred_username as principal")
    void securityConfigBeanWiresConverter() {
        var bean = new SecurityConfig().jwtAuthenticationConverter();
        Jwt jwt = jwt(Map.of("preferred_username", "alice",
                "realm_access", Map.of("roles", List.of("writer"))));

        var authentication = bean.convert(jwt);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo("alice");
        // Spring Security 7 appends a FACTOR_BEARER marker authority; the only ROLE_ must be ours
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_writer")
                .filteredOn(a -> a.startsWith("ROLE_"))
                .containsExactly("ROLE_writer");
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claims(c -> c.putAll(claims))
                .subject("sub")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
