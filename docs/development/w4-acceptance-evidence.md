# W4 Realtime Acceptance Evidence

Status: **PARTIAL — all local/test W4 acceptance journeys demonstrated except one
real-browser deep-link bootstrap detail, precisely documented below.**

Recorded: 2026-10-21. Verifying agent: W4-E integration & acceptance.
Scope: W4-B1/B2/B3/B4 realtime implementation against the frozen W4-A1 R3 contracts
(`shared/api-contracts/realtime-ticket.openapi.yaml`, `realtime-ws-v1.schema.json`,
`assessment-realtime-authority.openapi.yaml`, `shared/event-contracts/realtime-amqp-invalidation-v1.schema.json`,
[ADR-016](../decisions/ADR-016-gateway-realtime-authentication-foundation.md)).

Successful local acceptance is **not** production rollout authorization.

## Harness

`gateway/src/test/java/com/quizopia/gateway/W4RealtimeAcceptanceTopologyTest.java`
builds and owns a fully disposable loopback topology: real Identity, Classroom and
Assessment OS processes (Maven-built JARs), real PostgreSQL x3, Redis and RabbitMQ
containers, a least-privilege broker ACL (write-only publisher for Assessment,
read/declare-only consumer for Gateway), TWO real Gateway instances (one in-JVM, one
separate OS process with its own invalidation queue), real issued USER JWTs and
real client-credentials SERVICE tokens — including the dedicated
`gateway-realtime` client whose tokens Identity stamps with audience
`quizopia-assessment-realtime-authority`. A final journey drives a real Chromium
browser through Playwright against the same stack.

Command: `gateway> mvnw.cmd -B test -Dtest=W4RealtimeAcceptanceTopologyTest`.
The 14 protocol journeys run automatically in the standard `verify` suite. The
browser journey additionally requires `-Dw4.browser=true` (Playwright + frontend
dev toolchain, ~5 min extra).
Artifacts: `gateway/target/w4-acceptance-topology/*.log` (process logs; secrets
scanned), playwright log and results under `target/w4-acceptance-topology/`.

## Requirement/evidence matrix

| # | Requirement | Journey | Result |
|---|---|---|---|
| E1-1 | Default OFF fails closed at ticket + upgrade boundaries | `e1_journey1` (gateway started with no W4 flags; 503 `AUTHORITY_UNAVAILABLE` both surfaces) | **PASS (real)** |
| E1-2 | Ticket → WS upgrade → AUTH → Assessment subscription authorization | `e1_journey2` (real ticket, real AUTH, `ATTEMPT_OWNER` SUBSCRIBED via real authority call chain) | **PASS (real)** |
| E1-3 | One-use ticket + exact JWT binding | `e1_journey3` (replay → 4401; ticket A + JWT B → 4401) | **PASS (real)** |
| E1-4 | Origin restrictions | `e1_journey4` (foreign Origin → 403 ticket + 403 upgrade via raw HTTP/1.1 probe) | **PASS (real)** |
| E1-5 | Wrong role + revoked session | `e1_journey5` (STUDENT→PUBLICATION_TEACHER → correlated `NOT_ACCESSIBLE`; SQL+Redis-revoked account → decisive 401 at ticket issuance) | **PASS (real)** |
| E1-6 | Fail-closed dependency outages | `e1_journey6` (Assessment stopped → `AUTHORITY_UNAVAILABLE` per request, connection stays open; Identity stopped → ticket issuance fails closed; both restart cleanly) | **PASS (real)** |
| E2-1 | Committed attempt transition → V7 outbox → RabbitMQ → Gateway consumer → authorized WS | `e2_journey7` (submit → `RESOURCE_INVALIDATED` while eligible → `SUBSCRIPTION_REVOKED` once terminal; REST returns SUBMITTED) | **PASS (real)** |
| E2-2 | Publication OPEN-extension-CLOSED → teacher propagation | `e2_journey8` (extension → `RESOURCE_INVALIDATED` + REST revision bump; CLOSE → `SUBSCRIPTION_REVOKED` + REST shows CLOSED) | **PASS (real)** |
| E2-3 | Rollback/no-op exclusion + resource isolation | `e2_journey9` (rolled-back transition emits nothing; revision-only bump emits nothing; foreign student's attempt invisible to student A) | **PASS (real)** |
| E3-1 | Two Gateway instances | `e3_journey10` (in-JVM + OS-process instances, each with own queue; both sockets receive the same committed transition outcome) | **PASS (real)** |
| E3-2 | Duplicate/out-of-order hints | `e3_journey11` (duplicate eventId deduplicated; distinct eventId delivered; `eventId` echoed in frozen frame shape) | **PASS (real)** |
| E3-3 | Broker outage + recovery | `e3_journey12` (`rabbitmqctl stop_app`: business REST still 200, outbox retains PENDING, no hints; `start_app`: outbox drains, consumer reconnects with fresh queue and delivers) | **PASS (real)** |
| E3-4 | Gateway instance loss + reconnect | `e3_journey13` (killed instance: HTTP dies + sockets close; restarted twin re-authorizes with fresh ticket and delivers again) | **PASS (real)** |
| E3-5 | Missed-hint reconciliation on (re)connection/recovery | Covered in B4 frontend unit/integration suites (`use-realtime-regressions.test.tsx` reconnection + visibility journeys) and partially by E3-3/E3-4 socket recovery; browser-level confirmation blocked with E-BR below | **PASS (component), browser leg PARTIAL** |
| E3-6 | Unsaved drafts survive resyncs; stale responses cannot overwrite newer state | `attempt-realtime-integration.test.tsx` (invalidation → REST refresh with draft preserved) + `use-realtime-regressions` slow-refresh guard | **PASS (component), browser leg PARTIAL** |
| E4-1 | Broker ACL separation | `e4_journey14` (publisher cannot declare/consume; consumer cannot publish; live re-verification of permission regexes) | **PASS (real)** |
| E4-2 | No secret disclosure | `e4_journey14` (every issued ticket + JWT + broker password scanned against all process logs: absent) | **PASS (real)** |
| E4-3 | Operability telemetry + bounded resources | `e4_journey14` (`gateway_realtime_invalidation`/`gateway_realtime_subscription` metrics on the live gateway; outbox reaches PUBLISHED, zero QUARANTINED, PENDING drains to 0; bounded queues/registry asserted in B3 suites) | **PASS (real)**; Assessment prometheus is security-gated behind business-route authorization in the current configuration — see production gates |
| E4-4 | TLS expectations | Transport security configuration validation (HTTPS-only non-loopback, hostname verification) is enforced by unit suites in B3 (`RealtimeInvalidationConfigurationTest`, gateway fanout tests). The disposable topology intentionally uses loopback plaintext. | **PASS (validated by suites); production TLS is an operator gate** |
| E-BR | Real Chromium browser over the real stack | `e_browserJourney` + `frontend/tests/w4-real-e2e/realtime-browser.spec.ts` | **PARTIAL — see below** |

## Browser journey detail (the single unverified item)

Real Chromium against the real topology reached: UI login (200), `/api/auth/me`
hydration (200), authenticated `/app` routing — i.e. real browser → real Gateway →
real Identity authentication works end-to-end. The journey then fails on the first
full-page deep navigation (`/app/assessments/{id}`): the app's re-bootstrap calls
`POST /api/auth/refresh` and receives **403** reproducibly
(captured exchange: `refresh:403 → login:200 → me:200 → refresh:403`), producing the
app's "Account access is temporarily unavailable" boundary instead of the publication
view, so realtime status/REST-resync assertions in the browser never execute.

Evidence: `playwright.log` in the artifacts directory; error-context snapshots under
`playwright-results/`. The equivalent W3A disposable runtime performs the same
deep-link pattern successfully, so the defect is specific to this harness's
gateway/identity combination (leading suspicion: refresh-cookie handling across the
restarted in-JVM gateway instance), not to the W4 frontend client itself — whose
handshake, resync and draft-preservation behavior is fully covered by the passing
component suites. This remains the honest gap: **W4 browser-frame delivery was not
demonstrated end-to-end in a real browser within this acceptance run.**

Additional harness note (fixed during acceptance): Identity in this topology must
point its own resource-server JWKS at its actual origin (`spring.security.oauth2
.resourceserver.jwt.*`); with only the default `localhost:8081` value, `/api/auth/me`
returned 500. The harness now sets it, which is also what the W3A runtime does via
environment variables.

## Executed commands and results

- `gateway> mvnw.cmd -B test -Dtest=W4RealtimeAcceptanceTopologyTest` →
  **14/15 journeys pass** (browser journey runs on demand with `-Dw4.browser=true`).
- `gateway> mvnw.cmd -B verify -Dtest=!W4RealtimeAcceptanceTopologyTest` →
  **164/164 tests, BUILD SUCCESS** (full suite excluding the 10-min topology;
  the topology's 14 protocol journeys also pass inside the standard verify).
- `frontend> pnpm vitest run` → 63 files / 720 tests pass (plus 41 realtime tests).
- `frontend> pnpm typecheck / lint / format:check` → clean; `pnpm build` → compiles.

## Measured acceptance vs production-only operator gates

Measured locally (acceptance evidence): everything in the matrix above.

Explicitly **not** verified here and required before any production activation
(operator gates, unchanged):

1. Production TLS everywhere (gateway edge, broker, inter-service) with real
   certificates and hostname verification; loopback plaintext is test-only.
2. Proxy/ingress/L7 behavior and load/scale evidence for WebSocket fan-out,
   broker throughput and outbox drain under production traffic.
3. Production broker topology provisioning (vhost/exchange/queues/ACL) and
   monitoring/alerting wiring (lag, retries, quarantine, consumer health).
4. Assessment `prometheus` exposure path (currently authorized business routes
   only) if outbox telemetry must be scraped externally.
5. Security review sign-off on the real-browser refresh-cookie finding above.
6. Real-browser end-to-end invalidation journey (E-BR) once the harness defect
   is resolved; frontend behavior is otherwise component-verified.
