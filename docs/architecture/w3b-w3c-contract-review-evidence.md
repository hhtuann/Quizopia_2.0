# W3-B/W3-C — Contract review, delivery sequence and evidence

Status: **PROPOSED engineering contract pack complete for independent review; no Leader engineering sign-off or feature implementation.**

Recorded: 2026-10-13 (Asia/Bangkok). Product policy accepted in
[ADR-015](../decisions/ADR-015-w3b-w3c-leader-policy.md).
Already implemented: merged W3-A PR #78 at `8777d484645117b910fd9735ec2f8b8fd4eee4df`.
Deferred: Practice/guest/shuffle, generic inbox/email delivery, gradebook events,
future grading, AI monitoring and unrelated frontend work. External gates are
explicit below; documentation validation is not runtime evidence.

## Pack and source inspection

| Contract                                                                                                        | Purpose                                                                                         |
| --------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| [ADR-015](../decisions/ADR-015-w3b-w3c-leader-policy.md)                                                        | Accepted Leader product decisions and historical LD-01/04 amendments                            |
| [Lifecycle](../specifications/w3-b-attempt-lifecycle-contract.md)                                               | Count, creation/resume/retake, revision, saved/unsaved answers, deadlines and ABANDON           |
| [Closure/notices](../specifications/w3-b-closure-notification-contract.md)                                      | C1..C10, real inbox, notice proof, two actions, cancellation, batching/recovery                 |
| [Grading/results](../specifications/w3-c-grading-results-contract.md)                                           | Typed answers, deterministic exact v1 grade, aggregates, protected results/review               |
| [Persistence/concurrency](w3b-w3c-persistence-concurrency-design.md)                                            | Concrete proposed PostgreSQL schema, upgrade, guards, locks and full conflict matrix            |
| [HTTP](../specifications/w3b-w3c-http-contract.md) / [OpenAPI](../specifications/proposed/w3b-w3c.openapi.yaml) | 17 public plus1 internal proposed operations, strict DTOs, replay/errors/auth/pagination/config |

Inspected AGENTS.md/CLAUDE.md, required product/core/W3-A/ASSESS-15/Markdown docs,
open questions, system/service/authentication/LD-05/Publication architecture,
ADR-003/007/008/009/013/014, implemented source and tests. Specific evidence:

| Source                                                                                                                                                                                                                                                                                                                                                                                                 | Observed baseline and contract impact                                                                                                                                                                                                            |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| [PublicationController](../../services/assessment-service/src/main/java/com/quizopia/assessment/api/PublicationController.java) / [service](../../services/assessment-service/src/main/java/com/quizopia/assessment/application/PublicationService.java)                                                                                                                                               | Seven implemented method/path pairs, no CLOSE/Attempt/result/inbox. Strict configuration/revision operations and separate Student metadata projection.                                                                                           |
| [V1](../../services/assessment-service/src/main/resources/db/migration/V1__publication_core.sql) / [V2](../../services/assessment-service/src/main/resources/db/migration/V2__enforce_snapshot_publication_association.sql)                                                                                                                                                                            | Current duration5..240 and OPEN state-change prohibition require additive guard replacement. Same-ID forward/deferred reverse snapshot association must survive. No Attempt tables.                                                              |
| [JdbcPublicationStore](../../services/assessment-service/src/main/java/com/quizopia/assessment/persistence/JdbcPublicationStore.java)                                                                                                                                                                                                                                                                  | Publication FOR UPDATE for updates/open/schedule, local transaction/statement limits, durable creation digest, default maxAttempts absent. New operations share parent-first serialization.                                                      |
| [SnapshotValidator](../../services/assessment-service/src/main/java/com/quizopia/assessment/infrastructure/SnapshotValidator.java) / [source client](../../services/assessment-service/src/main/java/com/quizopia/assessment/infrastructure/PublicationSourceClient.java)                                                                                                                              | Strict full v1 source, sequential local question numbers and A-D, numeric spelling preserved, 2 MiB private handoff, bounded direct scoped SERVICE calls with original USER evidence. `-.25` is allowed by existing grammar.                     |
| [Implemented OpenAPI](../../services/assessment-service/contracts/publications.openapi.yaml)                                                                                                                                                                                                                                                                                                           | Remains unchanged. Proposed W3-B/C schemas only under docs/specifications/proposed.                                                                                                                                                              |
| [Gateway PublicationRoutes](../../gateway/src/main/java/com/quizopia/gateway/security/admission/PublicationRoutes.java) / [Assessment routes](../../services/assessment-service/src/main/java/com/quizopia/assessment/security/admission/PublicationRoutes.java)                                                                                                                                       | Closed exact W3-A inventories; wildcard transport prefix grants no future authority.                                                                                                                                                             |
| [Identity admission](identity-authority-admission.md) / [LD-05](ld05-enforcement.md)                                                                                                                                                                                                                                                                                                                   | USER/SERVICE distinction, fresh primary/complete Redis equality at both public boundaries, no positive cache/fallback, default-off controls and bounded in-flight authority limitation.                                                          |
| [Classroom authority service](../../services/classroom-service/src/main/java/com/quizopia/classroom/application/ClassroomAuthorityService.java) / [internal controller](../../services/classroom-service/src/main/java/com/quizopia/classroom/api/internal/ClassroomAuthorityController.java)                                                                                                          | Teacher ownership and evidence-derived persisted Student membership, no invite-as-membership or caller Student ID. Inbox deliberately has recipient-only ACL independent of membership.                                                          |
| [Identity V9 email outbox](../../services/identity-service/src/main/resources/db/migration/V9__create_email_verification_email_outbox.sql)                                                                                                                                                                                                                                                             | OTP email infrastructure exists; it is not a Student notification inbox or closure-delivery guarantee. No such inbox was found; offline-recipient eligibility also needs a new narrow proposed Identity adapter, not stored Student credentials. |
| [PublicationIntegrationTest](../../services/assessment-service/src/test/java/com/quizopia/assessment/PublicationIntegrationTest.java), [SnapshotAssociationIntegrationTest](../../services/assessment-service/src/test/java/com/quizopia/assessment/SnapshotAssociationIntegrationTest.java), [PublicationTopologyTest](../../gateway/src/test/java/com/quizopia/gateway/PublicationTopologyTest.java) | Existing real PostgreSQL constraints/races/upgrades and five-service Identity/Gateway/Quiz/Classroom/Assessment topology are templates for future evidence. Inspected, not rerun or claimed as W3-B/C passing tests.                             |

Git inspection: requested branch already existed clean at the exact latest fetched
origin/develop. Existing separate detached LD-05 review worktree was observed and
left untouched; no worktree created. Only open PR at initial search was separate
frontend #77; no duplicate contract PR found. CLI `gh` was absent; connected GitHub
tools are available for Draft PR creation. Git push uses normal non-force SSH.

## Leader policy coverage and reconciliation

| Policy                         | Contract coverage                                                                                                                                                          |
| ------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ASSESS-10                      | max0/unlimited, finite1..10/default 1/freeze, distinct ordinal, partial ACTIVE unique, explicit retake, all Attempts consume finite entitlement, highest graded aggregate  |
| ASSESS-11                      | Question SAVE, Attempt-wide revision, durable exact replay, changed/stale409 and serialized mutations/finalizers                                                           |
| ASSESS-12/17 + LD-01 amendment | Nullable unlimited deadline, bounded pinned deadlines, window/end separation, locked server-time eligibility, saved-only timeout, no post-OPEN mode switch                 |
| ASSESS-13 + LD-04 amendment    | Ordinary close active 409 sanitized, reconcile bounded; narrow delayed notice mechanism, no automatic CLOSED                                                               |
| ASSESS-16                      | Atomic final answer/result/policy/terminal write; matching immutable result replay, no placeholder result                                                                  |
| GRADE-07/08                    | Strict ASCII four-character wire grammar, exact decimal equality, strings, no intermediate rounding, display to 2 places HALF_UP only                                      |
| ABANDON                        | Explicit irreversible idempotent ungraded terminal, consumes count, excludes aggregate/review, pre-cutoff only                                                             |
| C1/C10                         | Real Assessment inbox; committed publication then separate accessibility confirmation; exact cutoff/status/rights/saved-only consequence; no read receipt/email dependency |
| C2/C3                          | Existing actions before cutoff, new mutation strictly before serialized sampled cutoff, races mapped with committed outcome                                                |
| C4                             | Separate Teacher REQUEST/COMPLETE, early ordinary close resolves workflow; workers never close                                                                             |
| C5/C6                          | Durable cancel fence at first committed forced grade, new request/new notice period, pending freeze/no new creation                                                        |
| C7/C8                          | Durable per-member progress/receipts, recoverable short batches, audit and distinct Teacher cutoff reason                                                                  |
| C9                             | Saved-only graded terminal submission, best-score/review eligible; abandon excluded                                                                                        |

Historical W3-A section 16 LD-01/04 is preserved verbatim. Current amendments are
separately linked; historical arithmetic numeric examples do not permit invalid
wire tokens. Open-question statuses distinguish accepted product, proposed
engineering review and unresolved release gates. No production code/schema/service
OpenAPI or frontend #77 changes. No new microservice or cross-service authority/FK.

## Security and privacy review requirements

| Threat/failure                                        | Required code behavior                                                                                                                                                                    | Infrastructure / release evidence                                                                                               |
| ----------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| Wrong Student Attempt/notification                    | Derive subject only from verified USER; owner/recipient predicate before state/detail; masked 404; no cross-user result/list                                                              |
| Other Teacher Publication                             | Issued+current TEACHER and DB owner; masked 404 before results/closure/action                                                                                                             |
| Removed roles, revoked/expired USER                   | Both Gateway/downstream independently admit current role; recheck expiry before writes; current removal denies replay too                                                                 | Real Identity authority/cutoff and browser topology; no JWT-only old nodes                                                      |
| Stale/missing Redis, Identity outage                  | Sanitized 503 AUTHORITY_UNAVAILABLE, no positive reuse, `/me` fallback, degraded read, refresh invalidation or silent logout                                                              | Reconciliation/fenced primary, durable commits/failover, pool/capacity and recovery drills                                      |
| Removed CLASS membership                              | Fresh private membership on Attempt/result/review/aggregate; masked denial, no mutation                                                                                                   | Real Classroom handoff/removal tests; documented in-flight snapshot limitation                                                  |
| Student cannot access notice after membership removal | Inbox recipient-only ACL, no CLASS or ACTIVE/window predicate; publish/confirm proof; no forced grade for missing notice; fresh narrow Identity recipient eligibility before confirmation | Real enabled inbox route readiness and authenticated access tests before REQUEST activation                                     |
| SERVICE impersonation                                 | ADR-014 TOKEN_USER on public routes, distinct narrow private SERVICE+USER evidence, reject wrong client/scope                                                                             | Provision least privilege, private network/TLS/caller allowlists                                                                |
| Malicious headers/paths                               | Strip/reject spoofed identity/evidence headers and duplicate Authorization; canonical paths/exact inventory; no public internal route                                                     | Proxy forwarding trust, CORS, no alternate exposed service ports                                                                |
| PUBLIC locator exposure/enumeration                   | Body-only private locator lookup and path association, no source/locator in Student/error DTOs, no public list                                                                            | LD78-02 restricted DB/backup/restore/operator access, encryption/secret management as required; proxy/APM/body/URI redaction    |
| Correctness inference/leak                            | Explicit safe DTO allowlists; no keys/correct flags/explanations/per-question score in delivery/save/result/error; review checks own SUBMITTED+CLOSED                                     | Serialized payload tests and logs/proxy/APM redaction; accepted immediate total feedback with unlimited retakes is not a defect |
| Notification cross-user leakage                       | Recipient-scoped pagination/detail, signed actor-bound cursor; no peer IDs/answers/locator                                                                                                | Runtime cursor-secret rotation/access audits                                                                                    |
| Cutoff manipulation                                   | Server-only notice time, committed-first accessibility proof, immutable exact 48-hour fields; strict DB clock sample after locks                                                          | UTC/time synchronization/clock anomaly detection and failover readiness; no client timestamp authority                          |
| Duplicate requests/stale workers                      | One live request partial unique, durable key/digest, state fence under parent, no old-cutoff reuse after cancel                                                                           | Multi-instance/restart/partial-batch fault tests and monitoring                                                                 |
| Insecure grading retry                                | Local atomic grade+result+terminal+receipt/audit, immutable terminal and unique result; replay returns stored result                                                                      | PostgreSQL transaction/commit-uncertainty/failover preservation                                                                 |
| Unbounded retake abuse                                | Authenticated per-actor operational throttles/body budgets, one ACTIVE, pagination; no fabricated finite limit                                                                            | Measure CPU/DB/storage demand, deploy reviewed rate/connection budgets and 429 behavior                                         |
| Unlimited ACTIVE resource use                         | PostgreSQL durable ACTIVE state, no permanent connection/session per Attempt, paged recovery and bounded work                                                                             | Capacity, storage and retention/deletion review; never terminalize for inactivity or hidden TTL                                 |

The last two table columns distinguish code requirements from operational evidence;
missing operational proof is not a claim the feature can be enabled. No sensitive
answer content/credentials/PII in audit logs/telemetry. Restricted durable audit
IDs are justified evidence, separate from ordinary logs and monitoring media.

## Dependency-aware implementation PR sequence

Four coherent backend increments are proposed. Atomic grading is moved before
public Student routes: exposing autosave/start with no reliable timeout finalizer
would leave deadline correctness incomplete. Each increment needs own tests,
independent review, Leader merge and current develop rebase/merge review.

| Increment                                | Deliverable and activation boundary                                                                                                                                                                                                                                                                                                         |
| ---------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| W3-B1 — Persistence/configuration        | Proposed V3/V4, compatibility, strict max/duration, Attempt binding/guards/receipts/result schema and default-off flags. Existing W3-A compatibility/upgrade tests. No new Student route activation; no fake result/close.                                                                                                                  |
| W3-C1 — Atomic grading/finalization core | Internal deterministic v1 grade, complete answer validator, submit/timeout shared transactional finalizer/result/aggregate/review projections and rollback/replay tests. Public adapters remain closed until Attempt core integrates all lifecycle authority/time invariants.                                                               |
| W3-B2 — Complete Student Attempt API     | START/read/RETAKE/SAVE/ABANDON plus integrated manual SUBMIT, bounded timeout and C1 score/aggregate/protected review routes; exact Gateway/downstream adapters; real PostgreSQL races and actual topology. Combined activation only after grader/finalizer present. Review cannot succeed until real closure exists, but cannot bypass it. |
| W3-B3 — Closure and real inbox           | V5; authenticated inbox/notice proof before REQUEST activation; ordinary CLOSE, REQUEST/CANCEL/COMPLETE, exact 48-hour, batch recovery/audit and Teacher result list, narrow Identity recipient-eligibility scope/adapter, multi-instance/time races/topology. Never enable a fake CLOSE between increments.                                |

B1 must reserve closure-related finalizer interface, but B3 schema/code is additive;
B2 cannot manufacture a cutoff without real B3 evidence. B3 activation additionally
requires compatible B2 recognizing notice cutoffs, even on delayed worker execution.
Optional email, gradebook result events and monitoring payloads are separate later
contracts; no event DTO is silently approved. Critical future integration events
must use transactional outbox/idempotent consumers under ADR-009. No event/config
change is executed in this assignment.

## Future runtime acceptance plan (not executed)

| Layer                      | Required tests and observable assertion                                                                                                                                                                                                                                                                                |
| -------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Pure grading/validation    | Four types, exact set/no partial, all-false/partial/null matrix, numeric accepted/rejected/ASCII trim/spelling/equality, exact totals/no rounding, HALF_UP presentation, unknown policy fails closed                                                                                                                   |
| PostgreSQL 17 migration    | Valid V1→V2→V3..V5 and fresh chain; V1/V2 checksums retained; existing OPEN JSON/snapshot unchanged, maxAttempts 1 fallback; invalid data fails without repair; null deadlines and no mode switch; raw DML FK/CHECK/immutability/total/audit bypass rejects                                                            |
| Attempt concurrency        | Separate real connections/barriers for every conflict-matrix row; one ACTIVE, exact finite count and ordinal, same-key retry after commit loss, save/submit/abandon/timeouts no partial result                                                                                                                         |
| Time                       | Inclusive start/exclusive end and strict deadline/cutoff at t−1µs/t/t+1µs; hold parent lock across cutoff to prove post-lock sampling; window ends without closure; unlimited resume after endAt; pinned bounded deadline unchanged after extension                                                                    |
| Closure notices            | Failed/queued/not-yet-committed/inaccessible notice cannot confirm or finalize; crash between publish and confirm; no read receipt/email; late confirmation gives separate 48 hours; removed membership still accesses inbox; cross-user404                                                                            |
| Closure recovery           | Staggered cutoffs, cancel before/after first committed forced grade, replacement fresh notices, stale worker fence, multiple worker instances, failure between batches, COMPLETE 202 replay/budget, all-terminal early close; no worker/timer CLOSED                                                                   |
| Result/visibility          | Highest graded tie deterministic, abandon excluded/null aggregate, previous best during retake, current owner/Teacher/member checks, answer-key omission across every pre-close DTO/error, own submitted-only review after durable CLOSED                                                                              |
| Actual HTTP topology       | Extend existing Identity/Gateway/Quiz/Classroom/Assessment with separate PostgreSQL 17 databases and Redis; real USER/Client Credentials, wrong scope/client/role/token/header/alias, membership removal, Identity/Redis/Classroom outage/stale restore; Quiz outage after opening must not block active content/grade |
| Browser integration (Dev2) | Real Gateway session handling, string revisions, explicit retake/abandon, saved-only timeout/closure notice, total-only OPEN, CLOSED review, current-role/outage recovery; mocks supplement but do not replace topology                                                                                                |
| Operational gates          | Bounded pools/lock contention/rate/storage, multi-instance recovery, primary failover preserving acknowledged receipts/security/results, TLS/redaction/backup/access, old-node draining, notice/inbox availability and clock behavior                                                                                  |

No new runtime tests were run for this documentation-only assignment. Historical
W3-A reported test totals remain evidence of that earlier increment only.

## Self-review findings and disposition

Severity convention: BLOCKER prevents correct freeze/implementation; HIGH risks
security/data/policy failure; MEDIUM leaves bounded ambiguity; LOW editorial;
NOT A DEFECT is accepted policy or an explicitly deferred external gate.

| Severity     | Finding                                                                                                    | Disposition                                                                                                         |
| ------------ | ---------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| HIGH         | Old text deferred unlimited duration/Student numeric grammar/ABANDON and disallowed narrow delayed closure | Fixed with ADR-015, explicit historical amendments and current-question reconciliation                              |
| HIGH         | Existing V1 OPEN guard would reject CLOSE                                                                  | Proposed additive replacement preserves protected fields/V2 and adds terminal checks; executable migration deferred |
| HIGH         | Recording notice time in an uncommitted/queued transaction could shorten 48 hours                          | Fixed committed-first publish then separate owner-accessibility confirmation; no forced action without evidence     |
| HIGH         | Throwing 409 after deadline grade could roll back reconciliation                                           | Fixed explicit committed-outcome requirement; runtime proof required                                                |
| MEDIUM       | Concurrent COMPLETE retries could exceed per-key batch budget or lose partial progress                     | Fixed IN_PROGRESS reservation, digest and per-unit progress under parent; stored 202 is not automatic closure       |
| MEDIUM       | Decimal/string or unlimited deadline ambiguities                                                           | Exact string representation, bigint Attempt wire strings, null deadline and finite SQL constraints specified        |
| NOT A DEFECT | Unlimited retakes plus immediate total score feedback                                                      | Explicitly accepted Leader product trade-off; correctness/key disclosure remains prohibited                         |
| NOT A DEFECT | Engineering review, retention/operational/monitoring release gates remain open                             | Clearly distinguished from accepted product and implementation; no activation claim                                 |

No new unresolved product choice is required to propose the bounded pack. General
inbox ownership transfer, continuous notice-uptime/pause rules, future rational
policies or broader retention product policy must receive their own decision if
introduced; none is silently implemented here. Independent review must verify
that notice accessibility and schema guards are implemented as specified before
calling production contracts frozen.

## Dev2 handoff and external release gates

Frontend PR #77 stays separate and untouched. Dev2 may review the proposed OpenAPI
and design mocks/types as explicitly provisional; do not ask Dev2 to consume these
nonexistent endpoints as live services. Generate/derive actual clients only from
reviewed implemented service OpenAPI at each backend handoff. No second divergent
DTO model. Public traffic uses Gateway/session infrastructure, no SERVICE token.

B1 handoff: compatibility/config duration 0/maxAttempts, explicit unmonitored CLASS,
no post-OPEN mode control. B2 handoff: verified actual route inventory, safe question/
answer DTOs, string revisions and operation-key retention, explicit resume/retake/
abandon and conflict reconciliation, total-only results/no answer keys. B3 handoff:
real inbox/status and exact cutoff, Teacher request/cancel/complete separate controls,
202 progress handling, ordinary active 409 without count, CLOSED review only. Include
concrete backend SHA, feature/provisioning state, real topology URL and observed
status before integration; UI must never assume mocks prove backend availability.

Release gates remain: independent engineering review/Leader sign-off; Identity
admission provisioning/reconciliation/fenced primary; all exact Gateway/downstream
coverage and actual topology; inbox accessibility activation evidence; Assessment
acknowledged-commit durability/failover; clock/latency/storage/rate capacity; TLS/
network/forwarded-header/redaction/confidential backup/operator controls (LD78-02);
snapshot/answer transport capacity (LD78-03); reviewed answer/result/notice/audit
retention/deletion; mixed-version drain/rollback; existing W3-M monitoring
privacy/refusal/loss/accommodation/media gates. None is satisfied just by these docs.

## Documentation validation evidence

Validation commands/results are recorded after the final self-review below. Only
Markdown formatting, schema/static references, source inventory, policy coverage,
whitespace and changed-file scope are in this assignment's executed check scope.
Repository validation excludes docs-only PRs; actual remote run status is reported
in the final handoff rather than inferred from local checks. No production tests,
migrations, feature activation, merge or auto-merge are authorized here.

Executed local checks (2026-10-13):

- Repository Prettier binary over all 20 changed Markdown/YAML files: PASS.
- openapi-spec-validator 0.7.2: PASS, 18 proposed operations (17 public/1 internal),
  32 schemas; no overlap with seven implemented W3-A method/path pairs.
- Draft 2020-12 answer-schema fixture validation: 35 cases PASS, including strict
  four-character ASCII numeric grammar, blank/null, malformed values and tagged
  union/duplicate-selection rejection. This validates schemas, not Java runtime.
- Static local-link/anchor resolution: 151 references PASS; all local JSON pointers
  resolved; each proposed response requires no-store and correct principal kind.
- Baseline comparison: historical LD-01/LD-04 sections byte-for-byte unchanged;
  every approved ASSESS/GRADE/C1..C10 policy represented; all changed paths docs-only.
- pglast 7.7 (PostgreSQL17 grammar): 29 proposed DDL statements/12 tables parsed;
  syntax only, no DB execution, migrations or database constraint proof.
- git diff --check and scoped secret/production-code review: PASS.

Commands use the repository Prettier installation; OpenAPI validator reused the
existing ignored W3-A tool bundle. SQL parser was installed only under /tmp.
The system Python could not create a venv because ensurepip was absent; reusing
isolated existing validator dependencies resolved this tooling issue. No repository
package/dependency/configuration changes. Runtime acceptance tests above remain
unexecuted, and remote CI status is reported separately after pushing.
