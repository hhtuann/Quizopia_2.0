# W3-M2 — LiveKit media engineering contract

Status: **DEPENDENT IMPLEMENTATION IN PROGRESS — STRICT PROVIDER REVOCATION
UNVERIFIED; DEFAULT GRANT ISSUANCE FAILS CLOSED; PRODUCTION NO-GO.** This is a
policy-neutral backend contract for the dependent implementation increment. No camera-refusal outcome, vendor approval,
real-learner collection permission or completed media acceptance is created.

## Baseline and dependency

Branch `feature/w3m2-proctoring-livekit` depends on M1 commit
`430dbdf` and [Draft PR #89](https://github.com/hhtuann/Quizopia_2.0/pull/89), whose
Assessment authority dependency is [Draft PR #88](https://github.com/hhtuann/Quizopia_2.0/pull/88).
These changes are unmerged; M2 does not pretend they exist on develop. The
[M1 contract](w3-m-proctoring-contract.md),
[accepted monitoring scope](w3-a-publication-scheduling-monitoring-policy.md),
[ADR-014](../decisions/ADR-014-access-token-principal-and-authority-contract.md),
[ADR-015](../decisions/ADR-015-w3b-w3c-leader-policy.md) and
[LD-05](../architecture/ld05-enforcement.md) remain authoritative.

The separate Assessment nomination remains `10615f4a9cb73ceb4bce7728f23bf210d0bc99a2`;
no monitoring SHA automatically joins an approved release scope. Formal RC remains
null and all eleven Assessment release gates remain open. This branch does not
appoint a Security reviewer, approve privacy or enable production.

## Ownership and scoped permissions

Proctoring owns durable media-room/grant metadata, server SDK orchestration and
recovery. M1 session/Attempt/Publication/Classroom/Student bindings remain immutable
and authority-derived. Identity/Assessment/Classroom remain authoritative for
current account/role/revocation, Attempt lifecycle/deadline and resource eligibility.
No cross-service SQL, answer state or media recording is introduced.

Every user operation requires independent RS256 USER validation, current LD-05
admission and fresh narrow resource authority. A valid JWT, known room/session UUID,
client-supplied role or previously issued media credential is not current permission.
No positive eligibility cache or internal Gateway proxy is permitted. Bounded
in-flight authority limitations remain explicit; no distributed atomicity is claimed.

| Principal                               | Required media privilege boundary                                                                                                                                                                               |
| --------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Eligible Student                        | Own ACTIVE bounded monitored CLASS Attempt; join only its server-bound room; publish camera only, no microphone/screen/data publishing, no subscription to peers, no moderation/room-list/recording privileges. |
| Authorized Teacher                      | Current owning Publication/Classroom authority; subscribe only to eligible corresponding monitored streams; no camera/audio/data publishing or broad room administration token.                                 |
| Backend operator SDK                    | Least-privilege server credentials stay private; reviewed room/participant operations, bounded network calls and sanitized errors. SDK service authority is never sent to the browser.                          |
| Unknown/unauthorized/unverifiable actor | Deny or sanitized unavailable; no generic authenticated fallback or degraded grants.                                                                                                                            |

Room names and participant identities must be opaque backend-generated identifiers,
not names, emails or other exposed user profile data. LiveKit records them in
infrastructure logs; identity-to-resource mappings remain in authorized Proctoring
storage. No answers, Quiz content or private credentials enter participant metadata.
[Official token/identity guidance](https://docs.livekit.io/frontends/reference/tokens-grants/).

## Actual SDK/provider semantics and the blocking distinction

The repository dependency is `io.livekit:livekit-server:0.16.0`. The
[official Kotlin/Java SDK](https://github.com/livekit/server-sdk-kotlin) supplies
server room APIs and token generation; use its actual methods/version rather than
speculative signatures. Server SDK bearer generation must explicitly set scoped
grants and short lifetime instead of inheriting permissive defaults. Supported
camera/source/subscription claims are defined in the
[official grants reference](https://docs.livekit.io/frontends/reference/tokens-grants/).

LiveKit token expiration controls initial join, not all reconnects; the server
refreshes connected-client tokens. Removing a self-hosted participant does not
invalidate previously issued credentials. Short TTL plus backend refusal of new
tokens therefore does not prove immediate terminal/revocation enforcement.
[Token lifecycle and self-hosted limits](https://docs.livekit.io/frontends/reference/tokens-grants/).

Cloud token revocation is a distinct provider feature, not an accepted deployment
choice. Official guidance warns the default cutoff buffer can leave a recently
refreshed token usable; strict rejoin rejection requires an explicit `revoke_token_ts`
with supported clock bounds. Validate actual original/refreshed/departed-identity
behavior and clock edges against the exact provider/SDK/server configuration.
[Explicit revocation cutoff](https://docs.livekit.io/intro/basics/rooms-participants-tracks/participants/#setting-an-explicit-revocation-cutoff).

**Strict provider revocation remains UNVERIFIED.** Configuring a provider string,
short TTL, worker or remove call is not proof. Default `UNVERIFIED` mode or disabled
recovery worker returns `503 MEDIA_REVOCATION_UNVERIFIED` on create/renew. Explicit
non-production `CLOUD_STRICT` plus a nonblank qualification reference and enabled
worker allows isolated synthetic mechanics; this configuration is an operator
attestation, not actual independent provider acceptance. Production-profile enabled
media is unconditionally rejected. The Cloud adapter can exist without selecting
or approving Cloud, accessing real Cloud resources or closing its acceptance gate.

## Durable orchestration and recovery requirements

Additive Proctoring Flyway V2 follows unchanged M1 V1 and adds per-session opaque
rooms and durable actor-bound grants. A room belongs to one monitored Attempt/session,
not an unrestricted whole-Publication room. Authorized Teacher grants target those
individual rooms; a frontend grid assembles authorized subscriptions without
Student peer subscription. The server generates random opaque `m-` room and `p-`
participant identifiers, never trusts client selections.

Persist room/session/actor/grant identity, lifecycle intent and relevant timestamps
in Proctoring's database; do not persist/log raw access tokens or API secrets.
Room creation, grant issuance, removal and cleanup must reconcile uncertain external
results to stable persisted intent. A retry must not silently widen grants, create
cross-room authority or resurrect a terminal local session.

Durable states are `PREPARED → ACTIVE → REVOKE_PENDING → REVOKED`; failed/abandoned
PREPARED work may go directly to the irreversible revocation fence. Preparing stores
intent before creating the provider room; create rechecks resource authority after
network work and before activation/signing. Grant actor/session/role/participant
binding cannot change. Only the actor that owns a grant can read/renew/revoke it;
Teacher authority does not confer ownership of a Student's grant. Repeated fencing
is safe; local pending intent is not proof of completed provider removal.

Local `leaseUntil` is the minimum of configured duration, current USER expiry and
Attempt deadline. Engineering duration is 1..30 seconds, default 10; this is a
local application lease, **not an SFU-enforced hard expiry during outage**, and it
does not overcome server token refresh/reconnect. No raw bearer is persisted. Create and renew both perform a bounded provider
`ensureRoom` liveness call, then repeat fresh resource authority before releasing
credentials. An enabled worker flag is a configuration prerequisite, not proof the
worker/provider remains live or that future outages will be fenced.

Recovery work is bounded, durable and default disabled. Provider outage or restart
must leave privileged issuance unavailable, preserve auditable pending cleanup and
avoid any Assessment mutation. A worker can assist reconciliation but cannot prove
that provider-side removal succeeded merely by marking a local row terminal. Verify
actual room/participant state and report unresolved work truthfully. The current
worker attempts at most ten due grants and ten ended rooms per tick, on a three-second
schedule only when explicitly enabled. Expired/deadline/ENDED sessions and PREPARED
work older than five seconds become candidates; provider failure preserves pending
intent for recovery instead of marking it completed. Grant and room retry metadata track
`last_recovery_at` and rotate least-recently attempted work, reducing the risk that
old failures consume every bounded batch. Current source stamps/orders both;
actual multi-instance/restart/failure fairness still needs its executed tests. These scheduling budgets are
engineering choices, not approved availability or revocation-delay guarantees.

The provider adapter passes integer Unix **seconds** for `revoke_token_ts` and
recomputes `floor(current Unix seconds) + 10` on every retry, including after
restart. The ten-second engineering guard covers the bounded three-second provider
call plus clock headroom; actual provider clock/skew/outage acceptance remains
unverified and the guard is not a revocation SLA.
Original minted tokens set `nbf = expiry - 31 seconds` with maximum 30-second local
lease to fence the original-mint race. This does not establish safe clock bounds,
Cloud refreshed-token revocation or a hard media deadline; those still require
actual provider-specific independent acceptance.

Local session END, terminal Attempt, deadline, eligibility/account/Teacher authority
loss and service recovery require fresh checks plus tested provider-enforced removal/
revocation. Previously minted or auto-refreshed tokens must not silently reopen
eligibility. Deleting a room or removing a participant alone is not a general
revocation guarantee; the unverified provider boundary keeps issuance closed.

## Technical states and unresolved product policy

Truthful permission granted/denied, missing/error device, unavailable camera,
media disconnected/reconnecting and ended-session observations can be represented
without deciding academic outcomes. Browser reports do not prove a published stream;
provider-connected state does not establish legal permission. No hidden capture,
fictitious active camera, recording, microphone requirement, strict screen sharing,
AI face identification or automatic misconduct classification.

PROCTOR-01 retention and PROCTOR-07 deployment/privacy remain open. PROCTOR-11
refusal/loss/accommodation policy and PROCTOR-12 privacy/accessibility/security
review block real-learner activation. Monitoring failure never changes answers,
submission/grade/deadline, auto-submits/ABANDONs or bypasses revoked authority.
Approved synthetic local media tests are separate from any real-learner outcome.

## Exact API and configuration

The [media OpenAPI](../../services/proctoring-service/contracts/media.openapi.yaml)
is the wire contract for these four full-prefix operations; Gateway forwards the
same exact paths without rewriting. All operations are bodyless and accept no query
string. Independent USER/current admission and fresh session/resource authority are
required; role is server-derived, never accepted in a browser body.

Let M be `/api/proctoring/monitoring/sessions/{id}/media-grants`:

| Operation               | Result / authority                                                                                                     |
| ----------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| POST M                  | Own eligible Student camera grant or authorized Teacher subscribe grant; configured qualification and worker required. |
| GET M/{grantId}         | Current authority plus matching session/actor grant ownership; local status only, no token.                            |
| POST M/{grantId}/renew  | Own ACTIVE, unexpired grant; revalidate current authority and qualification before extending within caps.              |
| POST M/{grantId}/revoke | Own grant and current authority; commit irreversible local REVOKE_PENDING before worker provider calls.                |

`Grant` has `id`, `url`, `token`, `leaseUntil`, `role`, `technicalState` (only
`GRANT_ISSUED`). `Status` has `id`, `leaseUntil`, `role`, `status` (PREPARED/ACTIVE/
REVOKE_PENDING/REVOKED). `GRANT_ISSUED` does not mean camera permission was granted,
a track is active or legal authorization exists. All outcomes are no-store and
sanitized. A returned token is a bearer secret held only in browser memory; never
persist it, place it in URLs, print it or send it to analytics. No generic LiveKit
administration endpoint or SERVICE credential is exposed. Teacher grants currently
require a known authorized monitoring session UUID; an authorized discovery/roster
adapter remains an engineering follow-up before complete frontend grid integration.

Actual defaults are `PROCTORING_MEDIA_ENABLED=false`,
`PROCTORING_MEDIA_WORKER_ENABLED=false`, `PROCTORING_MEDIA_REVOCATION_MODE=UNVERIFIED`,
blank qualification/API/public URL/key/secret values and
`PROCTORING_MEDIA_LEASE_DURATION=PT10S`. Private API uses HTTPS and client transport
uses WSS; insecure origins are explicit literal-loopback non-production exceptions.
An enabled media configuration requires M1 monitoring, validated origins, bounded
lease and private credentials; production is still rejected. Runtime/example
configuration does not supply approval or credentials.

## Validation and remaining acceptance

Seven focused SDK tests passed against an owned isolated **LiveKit server v1.9.0**,
including actual room operations, scoped JWT/permission checks and evidence that an
old cached self-hosted token can rejoin after removal. That negative security
observation is a reason to keep strict revocation unverified, not media acceptance.
It does not exercise real Cloud revocation or a browser camera grid. Final expanded
Proctoring/Gateway test counts and exact-HEAD CI remain to be recorded after execution.

Additional acceptance must cover decoded grants, own/other room, Student peer
subscription/publish-source isolation, current Teacher authority, terminal/deadline/
expiry, original **and refreshed** tokens, departed/never-joined identities, SDK/
provider cutoff units/clock boundaries, restart/recovery, unknown provider/unavailable
authority, token-log suppression and default-off behavior. Approved provider evidence,
qualified independent review and policy acceptance remain absent. No academic outcome
or actual Cloud qualification is inferred from synthetic SDK/provider doubles.

Official sources above were inspected 2026-10-14. Recheck version/provider semantics
before selecting topology or accepting revocation. Independent security review,
qualified external appointments, formal policy and target-specific authorization
remain required. **PRODUCTION NO-GO.**
