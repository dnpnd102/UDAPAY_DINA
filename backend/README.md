# Ledger Service (backend)

Spring Boot 4 / Java 25 service exposing the Udapay ledger.

## Endpoints

| Method | Path                                   | Access                        |
|--------|----------------------------------------|-------------------------------|
| GET    | `/actuator/health`                     | public                        |
| GET    | `/actuator/prometheus`                 | public                        |
| GET    | `/actuator/env`, `/actuator/info`      | any valid JWT (values masked) |
| GET    | `/api/v1/transfers`                    | any valid JWT                 |
| POST   | `/api/v1/transfers`                    | `ROLE_writer`                 |
| GET    | `/api/v1/transfers/search?username=`   | `ROLE_reader`                 |

Errors are RFC 7807 `application/problem+json` and include the request `traceId`.

## How each exercise is implemented

**Exercise 1 — SQL injection.** `TransferRepository.searchByUsername` is a JPQL
`@Query` with a `:username` named parameter (no `nativeQuery`). `TransferService.searchByUsername`
calls it and logs the lookup. `TransferRepositoryTest` and `LedgerApiIntegrationTest.SqlInjection`
prove that `admin' OR '1'='1` returns nothing unless a row's username is literally that string.

**Exercise 2 — Vault.** `bootstrap.yml` configures Spring Cloud Vault (host, port, token,
`backend: kv`, `default-context: udapay-ledger`, profile separator `/`, fail-fast).
`application.yml` has no `spring.datasource.password`; the key is read from
`kv/udapay-ledger/dev` (seeded by `infra/vault/init.sh`) and overrides application properties.

**Exercise 3 — Spring Security 7.** `SecurityConfig` declares `@EnableWebSecurity` and
`@EnableMethodSecurity`; the `SecurityFilterChain` enables CORS (`:5500`), disables CSRF,
is `STATELESS`, adds HSTS / CSP / `X-Frame-Options: DENY` / Referrer-Policy / Permissions-Policy,
permits only health and prometheus, and validates JWTs with a `JwtAuthenticationConverter`
backed by `KeycloakRealmRoleConverter` (`realm_access.roles` → `ROLE_*`).
`TransferController` carries `@PreAuthorize("hasRole('writer')")` / `hasRole('reader')`.

**Exercise 4 — Logging.** `CorrelationIdFilter` parses `traceparent`, puts `traceId`/`spanId`
into the MDC (UUID-derived values if absent/invalid), echoes the context back in the response,
and clears both keys in `finally`. `logback-spring.xml` uses `LogstashEncoder` with
`includeMdc=true` and the MDC keys `traceId,spanId`, ECS field names, and is referenced from `<root>`.

**Exercise 5 — CI/CD.** `../Jenkinsfile`; the OWASP plugin in `pom.xml` fails the build on CVSS ≥ 7.

## Build & test

```bash
./mvnw clean package            # runs the 46 tests on H2 (Vault disabled via test bootstrap.yml)
./mvnw org.owasp:dependency-check-maven:check
```

Tests mint RSA-signed JWTs shaped like Keycloak's (`support/TestJwtSupport`) so the full
resource-server pipeline — signature, issuer, expiry, role mapping, method security — is exercised.

## Configuration

| property / env                            | default                                   |
|-------------------------------------------|-------------------------------------------|
| `SPRING_DATASOURCE_URL`                   | `jdbc:postgresql://localhost:15432/udapay`|
| `SPRING_DATASOURCE_USERNAME`              | `udapay`                                  |
| `spring.datasource.password`              | **from Vault only**                       |
| `VAULT_HOST` / `VAULT_PORT` / `VAULT_TOKEN` | `localhost` / `8200` / (required)       |
| `KEYCLOAK_ISSUER_URI`                     | `http://localhost:9000/realms/udapay`     |
| `KEYCLOAK_JWK_SET_URI`                    | `…/protocol/openid-connect/certs`         |
