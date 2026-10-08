package com.udapay.ledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security 7 configuration: the ledger is a stateless OAuth2 Resource
 * Server that validates Keycloak-issued JWTs.
 *
 * <ul>
 *   <li>{@link EnableWebSecurity} — installs the servlet security filter chain.</li>
 *   <li>{@link EnableMethodSecurity} — activates {@code @PreAuthorize} on controllers.</li>
 *   <li>CORS on (origins :5500), CSRF off (no cookies/sessions), STATELESS.</li>
 *   <li>Defence-in-depth headers: HSTS, CSP, X-Frame-Options, Referrer-Policy, Permissions-Policy.</li>
 *   <li>{@code /actuator/health} and {@code /actuator/prometheus} are public; everything else needs a token.</li>
 *   <li>Keycloak {@code realm_access.roles} → {@code ROLE_*} authorities.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    public static final String CONTENT_SECURITY_POLICY =
            "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
    public static final String PERMISSIONS_POLICY =
            "geolocation=(), microphone=(), camera=(), payment=(), usb=(), interest-cohort=()";
    private static final long HSTS_ONE_YEAR_SECONDS = 31_536_000L;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
                // Browser clients served from http://localhost:5500 (see corsConfigurationSource)
                .cors(Customizer.withDefaults())
                // Bearer-token API: no cookies, no sessions → CSRF does not apply
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Defence-in-depth security headers
                .headers(headers -> headers
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .preload(true)
                                .maxAgeInSeconds(HSTS_ONE_YEAR_SECONDS)
                                // TLS terminates at Nginx, so emit HSTS on every request, not only HTTPS ones
                                .requestMatcher(AnyRequestMatcher.INSTANCE))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicyHeader(permissions -> permissions.policy(PERMISSIONS_POLICY))
                        .contentTypeOptions(Customizer.withDefaults())
                        .xssProtection(xss -> xss.headerValue(XXssProtectionHeaderWriter.HeaderValue.DISABLED)))

                // Authorization rules
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers("/api/v1/transfers/**").authenticated()
                        .anyRequest().authenticated())

                // Validate Keycloak JWTs and map realm roles to ROLE_* authorities
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));

        return http.build();
    }

    /** Allow the local front-end dev server (port 5500) to call the API with a bearer token. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:5500", "http://127.0.0.1:5500"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "traceparent", "tracestate"));
        config.setExposedHeaders(List.of("Location", "traceparent", "X-Correlation-Id"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    /**
     * Turns {@code realm_access.roles} into {@code ROLE_<role>} authorities and uses
     * {@code preferred_username} as the principal name.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        converter.setPrincipalClaimName("preferred_username");
        return converter;
    }
}
