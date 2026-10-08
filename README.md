# Udapay — Updating and Deploying a Core Banking API

A hardened version of Udapay's monolithic **Ledger Service**: zero-trust API security
(Spring Security 7 OAuth2 resource server against Keycloak), secrets in HashiCorp Vault,
ECS-style structured logging with W3C trace context, a multi-stage Docker build, and a
Jenkins pipeline with an automated CVE gate.

| Component            | Version        | Purpose                                     |
|----------------------|----------------|---------------------------------------------|
| Java / JDK           | 25 LTS         | Runtime (Generational ZGC)                  |
| Spring Boot          | 4.0.x          | Application framework                       |
| Spring Security      | 7.x            | OAuth2 resource server, JWT, method security|
| Spring Cloud Vault   | 5.0.x (2025.1) | Secret management via `bootstrap.yml`       |
| PostgreSQL           | 15 Alpine      | Relational database                         |
| Keycloak             | latest         | OAuth2 / OIDC authorization server          |
| Vault                | 1.15           | Centralized secret storage                  |
| Nginx                | Alpine         | Reverse proxy                               |
| Jenkins              | latest         | CI/CD                                       |

```
udapay/
├── backend/                        Spring Boot 4 ledger service (see backend/README.md)
│   ├── src/main/java/com/udapay/ledger/
│   │   ├── config/SecurityConfig.java            Exercise 3 — OAuth2 resource server + headers
│   │   ├── config/KeycloakRealmRoleConverter.java realm_access.roles → ROLE_*
│   │   ├── controller/TransferController.java    @PreAuthorize per endpoint
│   │   ├── exception/GlobalExceptionHandler.java RFC 7807 problem details
│   │   ├── filter/CorrelationIdFilter.java       Exercise 4 — traceparent → MDC
│   │   ├── repository/TransferRepository.java    Exercise 1 — parameterized JPQL
│   │   └── service/TransferService.java
│   ├── src/main/resources/
│   │   ├── bootstrap.yml                         Exercise 2 — Vault connection
│   │   ├── application.yml                       no DB password here
│   │   ├── logback-spring.xml                    Exercise 4 — ECS JSON logs
│   │   └── db/migration/V1__initial_schema.sql
│   ├── src/test/…                                46 tests (H2 + RSA-signed test JWTs)
│   ├── pom.xml                                   OWASP dependency-check (failBuildOnCVSS=7)
│   └── Dockerfile                                multi-stage, non-root, JRE 25 Alpine
├── infra/
│   ├── docker-compose.yml                        postgres, vault, keycloak, ledger-api, nginx
│   ├── .env.example
│   ├── nginx/nginx.conf
│   └── vault/init.sh                             seeds kv/udapay-ledger/dev
├── keycloak-init/realm-export.json               realm `udapay`, roles reader/writer/auditor
└── Jenkinsfile                                   Exercise 5 — Build→Test→Scan→Image→Deploy→Smoke
```

## Quick start (Docker)

```bash
cd infra
docker compose up -d
docker compose ps        # udapay_postgres, udapay_vault, udapay_keycloak, udapay_ledger_api, udapay_nginx
docker compose logs -f ledger-api
```

The one-shot `secrets-init` and `vault-init` containers exit after seeding; the five
long-running containers above stay up. The API takes ~60–90 s to report healthy
(Vault → Flyway → JWK fetch).

Rebuild after a Java/resource change:

```bash
cd infra && docker compose up -d --build ledger-api
```

## Getting a token

Three dev users are imported with the realm (change them in `keycloak-init/realm-export.json`):

| user  | password           | realm roles      |
|-------|--------------------|------------------|
| alice | alice-dev-password | reader, writer   |
| bob   | bob-dev-password   | reader           |
| carol | carol-dev-password | auditor          |

```bash
TOKEN=$(curl -s -X POST "http://localhost:9000/realms/udapay/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password" -d "client_id=udapay-client" \
  -d "username=alice" -d "password=alice-dev-password" | jq -r .access_token)
```

## Verifying the exercises

```bash
# Exercise 3 — security
curl -i http://localhost:8080/actuator/health                      # 200 UP (public)
curl -i http://localhost:8080/api/v1/transfers                     # 401 (no token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/transfers   # 200
curl -X POST http://localhost:8080/api/v1/transfers \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"amount": 100.50, "currency": "USD", "description": "rent"}'           # 201 (writer) / 403 (reader)

# Exercise 1 — SQL injection is inert (0 rows unless a username literally equals the payload)
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/v1/transfers/search?username=admin'%20OR%20'1'%3D'1"

# Exercise 2 — secrets come from Vault
export VAULT_ADDR=http://localhost:8200 VAULT_TOKEN=root-token-dev
vault kv get kv/udapay-ledger/dev
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/actuator/env | grep -i datasource
grep -rn "datasource.password" backend/src/main infra     # nothing but comments

# Exercise 4 — trace context in JSON logs
curl -H "traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01" \
     -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/transfers
docker compose logs ledger-api | grep 0af7651916cd43dd8448eb211c80319c

# Exercise 5 — the pipeline steps, run by hand
cd backend && mvn clean package -DskipTests && mvn test
mvn org.owasp:dependency-check-maven:check          # fails on CVSS >= 7
cd ../infra && docker compose up -d --build && curl -f http://localhost:8080/actuator/health && echo " OK"
```

## Without Docker

```bash
cd backend
mvn test                     # H2 in-memory, Vault disabled, RSA-signed test tokens
```

The test suite covers every rubric item that can be exercised in-process: parameterized
search with injection payloads, 401/403/200 per endpoint and role, all five security
headers, CORS for `:5500`, `traceparent` parsing and MDC clean-up, and the JSON log line
carrying the request's `traceId`/`spanId`.

## Secrets at a glance

| secret                         | where it lives                                   | committed? |
|--------------------------------|--------------------------------------------------|------------|
| PostgreSQL password            | generated by `secrets-init` → Vault `kv/udapay-ledger/dev` → API at startup | no |
| Vault dev root token           | `VAULT_DEV_ROOT_TOKEN_ID` env (dev-mode only)     | default only |
| Keycloak admin / demo users    | compose env / realm export (dev only)            | dev only |
