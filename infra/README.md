# infra — local stack

```bash
docker compose up -d
docker compose ps
```

| container            | image                           | port(s)      | role                                   |
|----------------------|---------------------------------|--------------|----------------------------------------|
| udapay_postgres      | postgres:15-alpine              | 15432→5432   | ledger database                        |
| udapay_vault         | hashicorp/vault:1.15 (`-dev`)   | 8200         | secret store                           |
| udapay_keycloak      | quay.io/keycloak/keycloak       | 9000         | OAuth2 server, realm `udapay` imported |
| udapay_ledger_api    | built from ../backend           | 8080         | Spring Boot API                        |
| udapay_nginx         | nginx:alpine                    | 80 / 443     | reverse proxy                          |
| udapay_secrets_init  | alpine (one-shot)               | –            | generates the DB password once         |
| udapay_vault_init    | hashicorp/vault (one-shot)      | –            | runs `vault/init.sh`                   |

## Secret flow

1. `secrets-init` writes a random 32-char password to the private `udapay_secrets` volume
   (only if it does not exist yet, so restarts keep the same DB password).
2. `postgres` reads it through `POSTGRES_PASSWORD_FILE`.
3. `vault-init` enables the KV v2 engine at `kv/` and stores
   `spring.datasource.username`, `spring.datasource.password` and an extra app key under
   `kv/udapay-ledger/dev`.
4. `ledger-api` starts with `VAULT_HOST=vault`, pulls that path through Spring Cloud Vault and
   connects to Postgres. No password appears in any file in this repository.

Inspect / rotate:

```bash
export VAULT_ADDR=http://localhost:8200 VAULT_TOKEN=root-token-dev
vault kv get kv/udapay-ledger/dev
vault kv patch kv/udapay-ledger/dev udapay.ledger.encryption-key=$(openssl rand -base64 32)
docker compose restart ledger-api
```

To rotate the DB password itself: `docker compose down -v` (drops both volumes) and `up -d`.

## Overrides

Copy `.env.example` to `.env` (git-ignored) to change DB name/user, the dev root token,
Keycloak admin credentials or the issuer URI.

## Nginx

`/api/*`, `/actuator/health` and `/actuator/prometheus` → `ledger-api:8080`;
`/realms/*`, `/resources/*`, `/admin/*`, `/js/*` → `keycloak:9000`.
A commented TLS server block is included for production certificates.
