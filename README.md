# walletx-finance

Java 21 / Maven / Spring Boot 4.1.0 foundation for WalletX financial functionality. Runs on port **8081** independently of walletx-auth (8080).

## Web stack

Spring MVC/Tomcat handles incoming requests because Spring Data JPA/JDBC is blocking. Spring Boot's WebClient starter provides the reactive HTTP client for future Setu and auth calls without installing a reactive server. A WebClient.Builder is available for constructor injection. Client connect/read timeouts are 5/15 seconds. This is not an end-to-end reactive application.

## Run

PostgreSQL must be running. This checkout uses the same local database as walletx-auth. The database password is held in Git-ignored `.local/finance.properties`, or can be supplied through environment variables:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:WALLETX_DB_URL = 'jdbc:postgresql://localhost:5432/postgres'
$env:WALLETX_DB_USERNAME = 'postgres'
$env:WALLETX_DB_PASSWORD = '<your-local-password>'
.\mvnw.cmd spring-boot:run
```

When the local password file exists, you can omit the password environment variable. On fresh clones it must be recreated locally, or configure the environment. Never commit database or Setu secrets.

Run `WalletxFinanceApplication` in IntelliJ with JDK 21, module walletx-finance, and working directory E:\walletx\walletx-finance. Alternatively import E:\walletx\pom.xml to see all workspace modules.

## Available functionality

- GET http://localhost:8081/actuator/health
- GET http://localhost:8081/actuator/health/readiness
- GET http://localhost:8081/actuator/health/liveness
- JPA/PostgreSQL connectivity, Bean Validation, Lombok annotation processing, Maven wrapper, packaged executable JAR.

Finance validates WalletX access tokens by calling auth's private introspection endpoint on each Bearer-authenticated request. There is no finance login, shared JWT signing key, or direct auth-table query. See the integration instructions below.

No business tables, migrations, or global default schema are introduced. Future entities must explicitly select their schema. Authentication entities are not imported and auth tables remain owned by walletx-auth.

Wallet CRUD, Setu credentials/API calls/webhooks, transaction ingestion, Kafka, and savings calculations are not implemented in this foundation.

## Verify

```powershell
.\mvnw.cmd verify
```

Tests run against the configured PostgreSQL connection, check the actual servlet HTTP server, health, Bearer authentication, role enforcement, inactive tokens, auth outage/timeout handling, and WebClient availability. A local HTTP auth stub makes finance tests independent of a running auth process. They execute SELECT 1 and create no business tables. H2 is not used.

## Auth integration

The local service credential has already been generated for this checkout. After cloning both repositories, configure it once:

```powershell
.\configure-auth-link.ps1
```

This writes matching random secrets into the two Git-ignored `.local/service-client.properties` files. It preserves existing matching keys and refuses to replace conflicting configuration. Alternatively set `WALLETX_INTROSPECTION_CLIENT_ID` (default `walletx-finance`) and `WALLETX_INTROSPECTION_CLIENT_SECRET` (minimum 32 characters, use a random secret) in both applications. This is separate from `WALLETX_JWT_SECRET`, which remains only in auth.

Start auth on 8080 and finance on 8081 in separate terminals (restart auth after adding this integration). Override `WALLETX_INTROSPECTION_URI` if auth runs on a different host/port. Only use HTTP for local development; deploy the private endpoint behind HTTPS and restrict network access to authorized services.

### Verify with Postman

1. Register/login at `POST http://localhost:8080/api/v1/auth/login` using your existing user credentials.
2. Copy the returned accessToken (not refreshToken).
3. Call `GET http://localhost:8081/api/v1/finance/me` with `Authorization: Bearer <accessToken>`. Expected 200 with userId and roles from the validated token.
4. Call `GET http://localhost:8081/api/v1/finance/admin/status`. USER gets 403; ADMIN gets 200.
5. Call `POST http://localhost:8080/api/v1/auth/logout` with that access token. Expected 204.
6. Repeat the finance /me call using the same token. Expected 401.

Missing, expired, modified, or revoked tokens are rejected. Auth also rejects stale token roles after a database role change; log in again for a fresh token. Finance does not cache active introspection responses, so requests checked after logout observe revocation.

The internal call is `POST /api/v1/internal/token/introspect` on auth with HTTP Basic service credentials and a form-urlencoded `token` body. Auth returns `active: false` for invalid tokens or `active: true` plus validated subject, roles, issuer, audience and timestamps. The endpoint does not return passwords, hashes, or refresh tokens. End users' Bearer tokens cannot authenticate this endpoint.

Finance maps validated roles to ROLE_USER/ROLE_ADMIN. Controllers receive OAuth2AuthenticatedPrincipal; use its sub claim as the owner ID for future account/transaction queries. Do not accept an owner ID supplied by the client as authorization.

The introspection connection/read timeouts are 2/3 seconds. Auth downtime, malformed upstream responses, or incorrect service credentials never grant access. Auth-service failures return 503; inactive tokens return 401; insufficient roles return 403. This synchronous HTTP check intentionally favors immediate revocation over availability and adds network plus service-authentication overhead. WebClient remains available for future Setu integration; introspection uses Spring Security's RestClient implementation.
