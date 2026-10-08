package com.udapay.ledger.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stands in for Keycloak during tests: a throw-away RSA key pair signs tokens
 * shaped exactly like Keycloak's (iss, preferred_username, realm_access.roles),
 * and a {@link JwtDecoder} bean validates them with the public half. Because
 * tokens travel through the real {@code Authorization: Bearer} header, every
 * part of the resource-server pipeline — signature, issuer, expiry, the
 * {@code realm_access.roles → ROLE_*} converter and {@code @PreAuthorize} —
 * is exercised end to end.
 */
@TestConfiguration
public class TestJwtSupport {

    public static final String ISSUER = "http://localhost:9000/realms/udapay";

    private static final RSAKey RSA_KEY;
    private static final RSAKey ROGUE_KEY;

    static {
        try {
            RSA_KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            ROGUE_KEY = new RSAKeyGenerator(2048).keyID("rogue-key").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    @Bean
    public JwtDecoder jwtDecoder() throws JOSEException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(RSA_KEY.toRSAPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        return decoder;
    }

    /** A valid token for {@code username} carrying the given Keycloak realm roles. */
    public static String token(String username, String... roles) {
        return mint(RSA_KEY, username, List.of(roles), Instant.now().plus(Duration.ofMinutes(5)), ISSUER);
    }

    /** Signed by the right key but already expired. */
    public static String expiredToken(String username, String... roles) {
        return mint(RSA_KEY, username, List.of(roles), Instant.now().minus(Duration.ofMinutes(5)), ISSUER);
    }

    /** Correct shape, wrong signing key — must be rejected. */
    public static String tokenSignedByRogueKey(String username, String... roles) {
        return mint(ROGUE_KEY, username, List.of(roles), Instant.now().plus(Duration.ofMinutes(5)), ISSUER);
    }

    /** Valid signature but issued by a different realm — must be rejected. */
    public static String tokenFromWrongIssuer(String username, String... roles) {
        return mint(RSA_KEY, username, List.of(roles), Instant.now().plus(Duration.ofMinutes(5)),
                "http://evil.example/realms/udapay");
    }

    private static String mint(RSAKey key, String username, List<String> roles, Instant expiresAt, String issuer) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(UUID.nameUUIDFromBytes(username.getBytes()).toString())
                    .audience("account")
                    .issueTime(Date.from(now.minusSeconds(1)))
                    .expirationTime(Date.from(expiresAt))
                    .claim("typ", "Bearer")
                    .claim("azp", "udapay-client")
                    .claim("preferred_username", username)
                    .claim("email_verified", true)
                    .claim("realm_access", Map.of("roles", roles))
                    .claim("scope", "openid profile email")
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(),
                    claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
