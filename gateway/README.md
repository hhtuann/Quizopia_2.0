# Gateway

## T3 owner-directory inventory extension

The exact `GET /classrooms` route forwards to Classroom without rewriting its path
or query; base and production route definitions include it. Both Gateway and
Classroom require USER + JWT/current `TEACHER` with fresh LD-05 admission. Existing
`POST /classrooms` and `GET /classrooms/{canonical-UUID}` authorization is preserved.
Classroom remains responsible for database ownership and the safe collection
projection; see its [directory contract](../services/classroom-service/contracts/classroom-directory.openapi.yaml).

Exact `GET /api/assessments/publications` now joins the existing Publication
inventory as `TEACHER`, using the existing Assessment route and
`PUBLICATION_ENABLED=false` default gate. This exposes Assessment's own safe,
owner-filtered FE-01 directory; Gateway does not implement ownership or pagination.
Wrong methods, aliases and future operations remain closed. Authority failure
returns 503; it never permits JWT-only reads. No runtime flags/secrets are changed.

Java 21 Maven `verify` with `-Dquizopia.build.directory=target/t3-directory-verification`
passed 181 tests with zero failures/errors and one on-demand W4 browser journey
skipped (`-Dw4.browser=true` is required). This includes PostgreSQL/Redis real
Identity/Classroom topology regressions, exact production routes, admission and
role enforcement, original USER forwarding, and closed-inventory tests.
After strengthening the collection/query assertions and the real-Identity collection
journey, a targeted `verify` rerun of `GatewayAdmissionIntegrationTest`,
`GatewayProductionRoutesTest`, `OwnerDirectoryInventoryTest` and
`RealIdentityAdmissionTopologyTest` passed all 36 tests without skips.

Independent Spring Boot 4.1.1 / Java 21 project for the Quizopia 2.0 platform scaffold.

Run independently from this directory:

- Windows: `mvnw.cmd test` or `mvnw.cmd verify`
- Unix-like shells: `./mvnw test` or `./mvnw verify`

The Gateway is the browser-facing HTTP edge. It routes `/api/auth/**` to Identity,
validates Quizopia bearer JWTs for protected requests, and permits anonymous access
only to the exact `POST` registration, email-verification, login, refresh, and
logout endpoints. `GET /api/auth/me` requires a Quizopia `TOKEN_USER` principal.

Browser CORS uses the explicit origins supplied by `GATEWAY_ALLOWED_ORIGINS` and
allows credentials. The local default is `http://localhost:3000`; production has
no default and fails startup for missing, empty, wildcard, malformed, or duplicate
origins. Gateway forwards `Cookie`, `Set-Cookie`, `Origin`, and `Authorization`
without implementing Identity session or credential behavior.
Gateway validates the issuer supplied by `IDENTITY_ISSUER` while obtaining
signing keys from `IDENTITY_JWKS_URI`; local development uses
`http://localhost:8081` for both Identity authority and JWKS host.

Identity remains the owner of the auth HTTP contract and its service-local
OpenAPI document. Gateway does not duplicate or aggregate those schemas.

Default local port: 8080. See `.env.example` and the root development documentation for configuration.

Spring Boot and Maven do not load `.env` automatically. For Windows local
development, copy the example and load it into the current PowerShell process
before starting Gateway from that same shell:

```powershell
Copy-Item .env.example .env
foreach ($rawLine in Get-Content -LiteralPath .env) {
    $line = $rawLine.Trim()
    if ($line.Length -eq 0 -or $line.StartsWith('#')) { continue }
    $separator = $line.IndexOf('=')
    if ($separator -le 0) { throw "Invalid .env entry" }
    $name = $line.Substring(0, $separator).Trim()
    if ($name -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') { throw "Invalid .env variable name" }
    $value = $line.Substring($separator + 1)
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}
.\mvnw.cmd spring-boot:run
```

The local Gateway values must match the Identity authority:
`IDENTITY_ISSUER=http://localhost:8081` and
`IDENTITY_JWKS_URI=http://localhost:8081/oauth2/jwks`. The Identity README
contains the key-generation, service startup, and register-to-`/me` smoke flow.

## LD-05 admission boundary

See the [exact protected-route inventory and deployment gates](../docs/architecture/ld05-enforcement.md).
Admission is independently disabled by default; selected Classroom operations
fail closed while disabled/unconfigured. Assessment and Proctoring business
routes remain denied until exact reviewed adapters exist. Provision this
deployment's own least-privilege SERVICE client and runtime secret. No Wave 3
business feature or Identity activation is enabled by this prerequisite.

Do not route activated protected traffic to bypass-capable older Gateway or
downstream instances. Drain/block old instances before activation or rollback.

## W4-B1 realtime authentication foundation

The native WebFlux edge reserves exact `POST /api/realtime/tickets` and
`GET /ws/realtime` surfaces. `REALTIME_ENABLED` defaults to `false`.
For controlled local tests only, set it to `true`, keep
`GATEWAY_ALLOWED_ORIGINS` exact, and provide working LD-05 Identity admission and
Redis configuration. The remaining `REALTIME_*` variables in `.env.example`
bound ticket TTL, AUTH timeout, frame size, issuance, upgrade and connection
capacity. Their defaults are engineering candidates, not production SLOs.

The upgrade carries only Origin and exact `quizopia.realtime.v1` negotiation;
never add ticket/JWT query parameters, cookies-as-auth, Authorization or custom
credential headers. AUTH occurs in the first text frame. Admission accepts
STUDENT or TEACHER sessions; resource authorization is never decided at the
edge. With the W4-B2 authority configured, every SUBSCRIBE is authorized by the
Assessment private `/internal/realtime/subscription-authorizations` decision
before `SUBSCRIBED`; without it (or on any authority uncertainty) SUBSCRIBE
fails closed with request-correlated `AUTHORITY_UNAVAILABLE`. Subscription IDs
are opaque, connection-scoped and bounded. There is still no broker fanout,
business-data delivery or frontend client in B2.

`REALTIME_AUTHORITY_*` variables in `.env.example` configure the dedicated
Gateway-to-Assessment client: the exact Identity-issued SERVICE credentials
(audience `quizopia-assessment-realtime-authority`, scope
`assessment.realtime.subscription-authorize`), private base URLs and strict
timeouts. `REALTIME_AUTHORITY_ENABLED` defaults to `false` and never relaxes
the B1 gates.

W4-B3 adds a default-off best-effort RabbitMQ invalidation consumer
(`REALTIME_INVALIDATION_*`). Each Gateway instance owns an exclusive
auto-delete queue bound to `quizopia.realtime.invalidation.v1`, deduplicates
event IDs, strictly validates the frozen envelope and fans out only to its own
authorized subscriptions after revalidating current Assessment authority.
Denied or stale authority revokes (`SUBSCRIPTION_REVOKED`); an unavailable
authority emits `RESYNC_REQUIRED` and REST stays authoritative. Broker hints
are never authorization evidence and carry no business data.

Production activation is prohibited until B3 integration, proxy/ingress and
load evidence, security acceptance and owner approval. See
[ADR-016](../docs/decisions/ADR-016-gateway-realtime-authentication-foundation.md).

## W3-B3 exact boundary

The eight implemented closure/results/inbox operations require independent
issued/current role admission at Gateway and Assessment. Teacher operations
require persisted ownership downstream. Inbox access is recipient-only and
does not require current CLASS membership. `CLOSURE_ENABLED=false`,
`RESULTS_ENABLED=false` and `ASSESSMENT_INBOX_ENABLED=false` gate their exact
operations independently. No private Identity eligibility route is proxied.
See [B3 evidence and Dev 2 handoff](../docs/architecture/w3b3-closure-notification-implementation.md).
