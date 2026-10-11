# LD-05 Identity authority admission

Status: **Leader-approved Candidate B architecture; Identity prerequisite merged at PR #75. End-to-end enforcement and operational activation remain incomplete.**

The Leader approved Candidate B decision points A–E in the security architecture
decision pack and explicitly authorized this bounded implementation assignment.
This records that approval without inferring its original approval date or
attributing it to PR #72, #73 or #74. The rejected journal/generation/one-second
lease design is not implemented. The coalescing work queue below is publication
work only; it is never authorization evidence or a global READY generation.

## Scope and ownership

Identity owns durable USER account eligibility, global roles, and revocation.
Required Wave 3 protected operations will always obtain primary Identity admission:
Assessment Publication management, authenticated CLASS/PUBLIC entry and delivery,
Attempt operations and protected result/review reads; required Classroom teacher
and membership authority checks; Proctoring session, evidence, camera-token and
viewer authorization. Each future adapter must enumerate its exact routes and
verify its own role, ownership, membership and resource lifecycle. Public
infrastructure that requires no business privilege remains independent.

This PR adds only Identity capability. Existing Gateway/business routes do not
yet enforce this protocol and must not be declared LD-05 compliant. Subsequent
Gateway and downstream PRs must independently validate USER JWTs, require
admission on every affected protected operation, and fail closed. Admission is
the primary path, never an outage fallback. No positive admission cache,
reusable assertion, JWT-only fallback, or degraded authenticated read is allowed.

## Complete authority contract, version 1

One Redis string at `quizopia:identity:user-authority:v1:<canonical-UUID>` contains
one atomic complete record, with no TTL or partial hash updates. Legacy sparse
cutoff keys remain unchanged. They cannot substitute for this record.

The seven pipe-delimited fields are schema version `1`, canonical UUID,
`PRESENT`/`ABSENT`, lifecycle, verification eligibility, complete roles, and exact
revocation cutoff. Lifecycle is `NULL` for durable SQL NULL or `VALUE:` followed
by unpadded base64url UTF-8 of the actual stored status. No account states are
added: only the existing `ACTIVE` status can admit; pending, null, disabled or
other stored statuses cannot. `VERIFIED`/`UNVERIFIED` is whether the durable
verification timestamp exists, not the timestamp or email itself. Roles use
existing Identity enum names, unique and lexically ordered, comma separated;
`NONE` explicitly represents an empty set. Cutoff is `NONE` or
`AT:<epoch-second>:<nanosecond>`. PostgreSQL microseconds are preserved exactly;
serialization also preserves nanoseconds without millisecond conversion.

Example:

```text
1|00000000-0000-0000-0000-000000000001|PRESENT|VALUE:QUNUSVZF|VERIFIED|STUDENT,TEACHER|NONE
```

The decoder requires all seven fields, supported schema, canonical UUID,
recognized field markers, known unique ordered roles and canonical timestamp
components. It rejects empty/missing fields, literal JSON null, unsupported
versions, extra fields and noncanonical encodings. Explicit SQL NULL lifecycle,
absent account, empty roles and absent cutoff are distinct typed facts. An absent
account must have null lifecycle, unverified email, empty roles and no cutoff.
Comparison uses validated Java records, never arbitrary JSON/string equality.
Profile, username, email and credential/session material are excluded.

## Admission boundary

1. Validate the caller's RS256 SERVICE JWT against Identity's configured public
   signing key and issuer, with required issuance/expiry timestamps and no expiry
   grace. Validate ADR-014 claims, exact client allowlist and admission scope.
2. Independently validate the original initiating USER JWT signature, issuer,
   timestamps and ADR-014 USER claims. Derive UUID exclusively from that token.
3. Read and strictly decode the actor's complete Redis record.
4. Execute one SQL statement on Identity PostgreSQL primary, in a fresh explicit
   READ COMMITTED transaction. Do not mark it read-only or route it to replicas.
5. Require complete typed equality of Redis and durable facts.
6. Require existing ACTIVE, email-verified account with current STUDENT role.
7. Reject a USER token whose `iat <= revoked_before` (inclusive).
8. Require the USER token's role set to equal the current complete role set.
   Grants require a normal refresh before use; removed JWT roles cannot retain
   privilege. This restriction is specific to the new admission endpoint and
   does not change existing login, refresh or `/me` behavior.
9. Return only actor UUID and current roles. The owning business service checks
   the required role in both its USER JWT and admitted current roles, plus all
   resource/ownership rules. Recheck USER expiry before returning success.

`PrimaryUserAuthorityQuery` uses JDBC, not lazy entities or
`CurrentUserService.findEligible()`. One statement obtains requested UUID,
account existence/status/verification, all roles using an array subquery, and
cutoff with joins. It also checks `pg_is_in_recovery()` and transaction isolation
in the same statement. No follow-up role/revocation query is performed.
REQUIRES_NEW prevents an outer repeatable-read transaction pinning old authority.
Durable malformed facts/query errors/timeouts deny availability.

The linearization point is acquisition of that PostgreSQL statement's MVCC
snapshot. Changes committed before it must be represented; uncommitted/later
changes need not be. Reading Redis first cannot bypass a newer durable snapshot:
a stale or partially restored Redis record produces a mismatch. A change after
the snapshot can race an already admitted request. Identity admission and a
later business commit are **not a distributed transaction** and do not provide
atomic cross-service revocation. No undocumented freshness lease is claimed.

Primary deployment must fence old writers, route to exactly one writable primary,
and promote only an authority history containing acknowledged security commits.
Replica replay lag or promotion that loses acknowledged revocations is not covered
by Redis equality. Synchronous/RPO-zero security durability and failover drills
are production gates; the SQL replica check alone cannot establish those guarantees.

## Internal API and credentials

`POST /internal/security/user-admissions` accepts no body or query parameters.

- `Authorization: Bearer <SERVICE JWT>` is the caller credential.
- `X-Quizopia-User-Evidence: <original compact USER JWT>` is sensitive initiating
  evidence, distinct from the caller credential. It has no `Bearer` prefix.
- Require exactly one of each header; reject duplicates, comma-combined or
  malformed presentations. Never forward refresh cookies; cookies are rejected.
- Require `principal_type=SERVICE`, exact allowlisted `sub`, and the registered
  least-privilege `identity.user-admission` scope. Roles never substitute for scope;
  a browser USER in Authorization receives 403. No recursive admission check is
  performed for this SERVICE principal.
- Scope registration uses the existing service-client registration capability;
  this PR does not seed credentials, add a public provisioning API, or grant
  admission scope to existing clients automatically.
- USER evidence requires `principal_type=USER`, canonical UUID and valid roles,
  without service scopes. A SERVICE token cannot substitute for USER evidence.
- Separate filter-chain matching isolates `/internal/security/**` from public
  authentication and Authorization Server chains. The endpoint is omitted from
  browser-facing Swagger. No Gateway route or public CORS header is added.
- Success: `200 {"actorId":"<UUID>","roles":["STUDENT"]}`.
  Every response uses `Cache-Control: no-store`. There is no nonce, cached
  assertion, profile, answer-bearing content, or reusable authorization token.

Sanitized errors contain only `{"code":"<fixed-code>"}`:

| Condition                                                                                                                       | Status | Code                             |
| ------------------------------------------------------------------------------------------------------------------------------- | ------ | -------------------------------- |
| Invalid/missing SERVICE authentication, signature, issuer, expiry or claims                                                     | 401    | `SERVICE_AUTHENTICATION_INVALID` |
| Valid principal but wrong type, client or scope                                                                                 | 403    | `SERVICE_CALLER_FORBIDDEN`       |
| Missing/duplicate/malformed USER header, body, query or cookie                                                                  | 400    | `INVALID_ADMISSION_REQUEST`      |
| Invalid/expired USER signature/claims; ineligible, revoked or outdated-role USER after matching authority                       | 401    | `USER_EVIDENCE_INVALID`          |
| Disabled capability, insecure transport, Redis missing/malformed/unavailable/mismatched, unknown schema, or primary uncertainty | 503    | `AUTHORITY_UNAVAILABLE`          |

Mismatch is evaluated before account eligibility/cutoff and returns 503; a
complete matching revoked/disabled actor returns USER 401. Upstream SERVICE
401/403 and timeouts must later be translated to sanitized browser-facing
upstream/unavailable errors, never incorrectly reported as invalid browser USER
credentials. Outages do not revoke refresh families or clear cookies.

## Credential propagation and deployment trust

Caller and destination allowlists must be exact. Forward original USER evidence
only to this Identity destination and specifically reviewed downstream dual
credential endpoints. This is trust in an authenticated caller's bounded use of
an independently signed bearer token; possession of a USER JWT permits replay.
A nonce by itself would not supply delegated-user cryptographic authorization.
Protect service secrets and forwarded evidence against caller compromise, SSRF,
unintended redirect/forwarding, browser spoofing and token replay. There is no
blanket permission for services to perform Teacher USER commands.

Authenticated encrypted transport, private routing/network policy, certificate
validation and destination pinning are mandatory before deployment. The endpoint
requires secure requests by default and never redirects credentials to TLS.
Controlled localhost integration tests explicitly disable that check; they prove
no production transport security. If TLS terminates at an internal proxy, configure
trusted forwarding and prevent untrusted `Forwarded`/`X-Forwarded-*` spoofing.
Keep Identity's internal endpoint inaccessible through the public edge.

Never put either token in query strings, logs, traces, ordinary audit records,
metrics labels, persistent requests, exception messages or support captures.
Redact Authorization, Cookie and X-Quizopia-User-Evidence at ingress, proxies,
APM and HTTP clients as well as Identity. Request/access logging must suppress
credentials and query strings before activation. No sensitive header is added
to public browser CORS. Service JWT lifecycle remains short-lived expiry;
USER cutoffs do not immediately revoke already-issued SERVICE JWTs.

## Publication, startup and recovery

Flyway V13 creates `user_authority_work` (actor UUID, opaque work identity and
oldest queued time), its due-work index, and transaction-local triggers on:

- account insert/delete and updates of lifecycle/verification;
- all role insert/update/delete operations;
- all durable USER cutoff insert/update/delete operations.

This covers registration, trusted activation/OTP verification, teacher self-grant
and durable revocation, plus direct SQL mutations and future administrative
writers of these tables. There is currently no separate production admin account
mutation API. Google linking does not mutate these authority facts. Profile-only
updates do not enqueue authority work. Transaction rollback also rolls back work.
Triggers do no Redis I/O and add no application post-commit dependency.

Work coalesces per actor. Each durable change replaces an opaque work ID;
acknowledgement deletes only that observed ID. This ID fences work deletion,
not authorization, and does not appear in the authority facts. The worker reads
committed primary facts, atomically SETs the complete projection, then
acknowledges work. Failure/crash leaves retryable work; crash after SET is safely
repeated. No lease or global readiness generation is required. Multiple instances
may duplicate work. Delayed publishers can overwrite a newer projection, which
can temporarily deny an otherwise eligible actor; the mandatory primary equality
check always prevents this stale write from authorizing different durable facts.

A separate keyset sweep reconstructs up to one bounded page of current actors
per poll, even with no work hints. It covers cold Redis, historical actors,
restores, missed hooks and an older publisher writing after another worker
acknowledged work. Queue and sweep each process at most configured batch size.
The sweep uses the primary-key index, remembers only a local cursor, wraps after
completion and restarts from the first page after instance restart. No admission
initiates repair or scans a table. One actor can resume once independently
verified equal on its next admission, regardless of other actors' recovery.

Admission and the scheduled worker are separately opt-in and disabled by default.
After application readiness/migration the worker begins after one configured polling interval;
there is no global READY or PING gate. A dedicated daemon executor isolates recovery from email delivery scheduling. Shutdown interrupts polling and checks interruption between records;
interrupted/unacknowledged work survives. Sweep cursor loss is harmless and
restarts bounded traversal. Reconciliation never changes durable authority.
Deleted actors use queued explicit absent records; accounts are not recreated by
reconciliation. Operators must not TRUNCATE authority tables, disable triggers or
reuse actor UUIDs as normal mutation mechanisms. Future writers/schema changes
must maintain trigger coverage, queue semantics, complete fact schema and tests.

No arbitrary recovery SLA is promised: capacity depends on primary/Redis health,
actor count, page size, polling, mutation load and number of instances. Metrics
`identity.admission{outcome}`, `identity.authority.publication{outcome}` and
`identity.authority.reconciliation{outcome}` use fixed labels without actor data.
Operators inspect queue age/backlog via controlled database tooling. Counters
are not proof that all actors are recovered.

## Operational and rollout gates

1. Review V13 on clean and upgraded PostgreSQL 17; retain trigger/function
   permissions, queue durability and primary-only routing. Schema rollback must
   disable capability/worker first; never silently remove authority checks.
2. Set an explicit exact client allowlist, register only needed admission scope,
   distribute each client's secret privately and verify real signed tokens.
3. Validate TLS/certificates, private destination/network policy, header redaction,
   trusted proxy configuration, body/header limits and browser isolation.
4. Enable reconciliation independently; observe queue backlog/age, failures,
   database/Redis latency and recovery under outage and stale restore.
5. Load-test one primary snapshot query plus one Redis GET per protected admission,
   OAuth token reuse by callers, RSA validation, connection pools, statement/
   network timeouts, worker capacity, queue contention and multi-instance behavior.
   Defaults (50 records per work/sweep page, five-second poll, two-second SQL
   timeout) are bounded controls, **not a freshness guarantee or capacity result**.
   Query timeout accepts 1–30 seconds; batch size 1–500; poll at least one second.
   Redis connect/command timeouts default to two seconds. Configure bounded
   Hikari acquisition and PostgreSQL connect/socket/cancel timeouts for the
   deployment; an upstream request deadline must fail closed across all phases.
6. Review failover without lost security commits and verify recovery after
   process death, commit/publication gaps, restores and authority mutation races.
7. Merge reviewed Gateway/downstream adapters, route inventories and real-topology
   E2E denials for Assessment/Classroom/Proctoring, with no fallback or caching.
8. Security/Leader rollout approval is required before production activation.
   This Identity prerequisite alone neither enables W3-A APIs nor authorizes a
   production release. W3-B/C/M1/M2 and product/privacy gates remain open.

The subsequent [Gateway/downstream enforcement inventory](ld05-enforcement.md)
records bounded adapter coverage and future default-closed boundaries. It does
not change this Identity contract or authorize production activation.
