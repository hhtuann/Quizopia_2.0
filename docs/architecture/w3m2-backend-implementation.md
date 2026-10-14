# W3-M2 backend checkpoint and resumption

Status: **DEPENDENT M2 ENGINEERING MECHANICS IMPLEMENTED; STRICT PROVIDER
REVOCATION/HARD-OUTAGE ENFORCEMENT UNVERIFIED; FUNCTIONAL DELIVERY PARTIAL;
ENGINEERING ACCEPTANCE NOT READY; PRODUCTION NO-GO.** No provider selection,
Cloud execution permission, independent approval or real-browser camera acceptance
is supplied by this checkpoint.

## Source baseline and dependency chain

Branch: `feature/w3m2-proctoring-livekit`, based on M1 HEAD `430dbdf` in
[Draft PR #89](https://github.com/hhtuann/Quizopia_2.0/pull/89), which depends on the
narrow Assessment authority [Draft PR #88](https://github.com/hhtuann/Quizopia_2.0/pull/88).
The original develop baseline is `e5c4ef3500f1ce40303af161cd6ca74b706c21b2`; neither
unmerged dependency is portrayed as available on develop. M2 must retain its actual
dependent PR base. Final M2 commit/PR/exact-HEAD CI belongs in its report after push;
no future self-SHA or success is invented.

The [M1 checkpoint](w3m-backend-implementation.md) preserves that increment's actual
local evidence. The [M2 contract](../specifications/w3-m2-media-contract.md),
[media OpenAPI](../../services/proctoring-service/contracts/media.openapi.yaml) and
[service README](../../services/proctoring-service/README.md) specify current mechanics.
Assessment nomination remains `10615f4a9cb73ceb4bce7728f23bf210d0bc99a2`; formal RC
remains null. A combined release scope/RC requires actual new approval and revalidation.

## Implemented bounded mechanics

Additive Proctoring V2 follows unchanged M1 V1, persisting opaque per-session rooms
and actor-bound grant intent. Four exact bodyless/query-free USER operations create,
read, renew and revoke own grants with independent current admission and fresh
Assessment/Classroom authority. SDK `io.livekit:livekit-server:0.16.0` handles rooms,
scoped Student camera-only/no-peer-subscription tokens, Teacher subscribe-only tokens,
participant removal and room cleanup. Create/renew check provider room liveness and repeat fresh authority after network
work; the worker-enabled flag is not continuous liveness evidence. No raw JWT is persisted; returned credentials
must remain only in browser memory.

Durable PREPARED/ACTIVE/REVOKE_PENDING/REVOKED fencing and bounded default-off
recovery reconcile uncertain provider work. Grant and room `last_recovery_at` metadata
rotate retry candidates. Local lease is capped by configured duration, USER expiry
and authoritative Attempt deadline; it is not an SFU hard expiry during outage.
Integer-second explicit provider cutoff, original-token mint fencing and current
retry guards are documented as engineering mechanics, not accepted Cloud/clock/
refresh guarantees. No automatic academic outcome or Assessment answer mutation.

## Current evidence boundary

Seven focused official-SDK tests against owned self-hosted LiveKit v1.9.0 passed,
including room/permission behavior and demonstrated old cached token rejoin after
removal. That negative security observation keeps provider enforcement unverified;
it cannot PASS Cloud-only revocation, active-session deadline enforcement during
provider outage or browser camera-grid E2E.

Fresh current-source Proctoring `spotless:check verify` completed successfully:
**158 tests across 16 suites, zero failures/errors/skips**, 49 Java files clean,
package built. Report: `services/proctoring-service/target/w3m2-full-verification/surefire-reports`;
private local log `/tmp/w3m2-full-verification.log`.

The new owned suites contain 38 tests: actual SDK/LiveKit 7, SDK HTTP wire 6,
real PostgreSQL 17, and application HTTP 8. Application/recovery port tests add
21; route/DTO/configuration tests add 2. The historical 97 tests also pass in this
fresh run. PostgreSQL covers V1-to-V2 upgrade with checksum/data preservation,
concurrency, caller/deadline caps, irreversible fences, uncertain retry, lock timeout,
and grant/room retry fairness. Actual HTTP uses real RS256/PostgreSQL and explicit
Identity/Assessment/provider doubles, including root DEBUG credential redaction;
it does not prove the complete real-service topology or provider qualification.
All containers owned by this Proctoring run were removed; unrelated infrastructure
and concurrent Gateway test containers were preserved.

Executed from the repository root:

```bash
env MAVEN_USER_HOME=/home/hhtuann/.local/share/quizopia-dev/maven MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2' bash services/proctoring-service/mvnw -B -f services/proctoring-service/pom.xml -Dquizopia.build.directory=target/w3m2-full-verification spotless:check verify -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
```

Relevant earlier Assessment full verification remains 352/0/0/0; this M2 diff
changes no Assessment source, migration or public API. Fresh Gateway `spotless:apply verify` also completed: **108 tests across 17 suites,
zero failures/errors/skips**, package built. Reports:
`gateway/target/w3m2-full-verification/surefire-reports`; private local log
`/tmp/w3m2-gateway-full.log`. This includes the existing real Identity/Classroom/Quiz/
Assessment topology and exact media default-off boundary tests, not a full real
Proctoring media topology. Exact-HEAD CI remains separate until retrieved.

```bash
env MAVEN_USER_HOME=/home/hhtuann/.local/share/quizopia-dev/maven MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2' bash gateway/mvnw -B -f gateway/pom.xml -Dquizopia.build.directory=target/w3m2-full-verification spotless:apply verify -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
```

OpenAPI structure/record-field parity and static checks support this increment,
not independent media security acceptance.

Regression development exposed and corrected reused-grant fresh USER expiry,
Unix timestamp units, provider-delay revocation cutoff, renewal outage recheck,
retry starvation, and uncertain room-delete acknowledgment. Strict duplicate/trailing
provider error parsing fails closed. A new migration test initially counted Flyway's
null-version schema metadata; only that inventory fixture was corrected. A temporary
route-audit Java helper initially had an escape error; its corrected actual compiled
inventory check passed. Gateway's uppercase-negative fixture used an all-numeric UUID;
it now uses an actual uppercase alphabetic UUID. No runtime gate or test was weakened.
Agent self/delegated review found no remaining actionable source defects; this is
not qualified independent Security/Privacy review.

## Executed supporting checks

- Three OpenAPI 3.1 structures validated; nine compiled Gateway/downstream
  route/role adapters matched; exact private/unknown operations stayed closed.
- Prettier checked all ten changed Markdown/YAML paths with the installed
  `frontend/node_modules/.bin/prettier`; no dependency installation. A bare `pnpm`
  attempt was unavailable on this shell PATH and is not reported as PASS.
- `docker compose config --quiet` and `git diff --check` passed.
- The existing release validator and all 60 release-register safety tests passed;
  historical 59-gate matrix, schema 2 and eleven open gates are unchanged.
- A temporary scoped audit (`/tmp/w3m2-scope-audit.py`, not a repository command)
  passed protected-file SHA-256 equality for 16 files, shared Proctoring V1 byte
  equality, 26 disabled feature/admission defaults, 97 local Markdown references,
  Python syntax and high-confidence credential-signature checks. No frontend,
  build artifact or production-profile edit is included. These static checks do
  not qualify a deployment or detect every possible secret.

```bash
PYTHONPATH=gateway/target/w3a-evidence/openapi-python python3 -B services/assessment-service/contracts/validate-w3-production-release.py
PYTHONPATH=gateway/target/w3a-evidence/openapi-python python3 -B services/assessment-service/contracts/test-w3-production-release.py
docker compose config --quiet
git diff --check
```

## Default-off and external acceptance boundary

`PROCTORING_MEDIA_ENABLED=false`, `PROCTORING_MEDIA_WORKER_ENABLED=false`, default
`UNVERIFIED` mode and blank qualification references keep issuance unavailable.
Explicit non-production qualification configuration is only a synthetic operator
attestation. Production-profile enabled monitoring/media remains unconditionally
rejected pending actual M2/policy/security integration. No flag or reference string
appoints an external reviewer or selects LiveKit Cloud.

Concrete remaining work:

- **ENGINEERING/DEPENDENCY:** authorized Teacher session discovery/roster is missing;
  current grant APIs require already-known monitoring session UUIDs. Implement bounded
  current owned-Publication/Classroom discovery without answer leakage or arbitrary
  enumeration before claiming frontend Teacher grid completeness. Also pending:
  authoritative Assessment answer-change event adapter;
  actual Proctoring/Assessment/Classroom/Identity/Gateway topology with real issued
  tokens and private negative checks; dependent PR review/merge by Leader.
- **SECURITY/ENVIRONMENT:** actual provider original/refreshed/departed-identity
  revocation, clock/cutoff boundaries, restart/partition and provider-enforced active
  session limits during control-plane outage. App lease, token TTL and local fencing
  cannot substitute for this evidence. No suitable accepted provider/design yet.
- **CONTRACT/SECURITY:** PROCTOR-01 retention/access/erasure; PROCTOR-11 refusal/loss/
  accommodations; PROCTOR-12 qualified privacy/accessibility/security acceptance;
  PROCTOR-07 deployment/location. No TTL, sanction, recording or legal basis invented.
- **DEPENDENCY:** actual frontend camera/Teacher grid permission/isolation/reconnect
  E2E and independent frontend PR #77 review. Backend SDK tests do not certify them.

The [eleven-gate table](w3m-backend-implementation.md#eleven-unchanged-assessment-release-gates)
and [authoritative register](../development/w3-production-evidence-register.yaml)
remain unchanged: **6 BLOCKED / 5 NOT VERIFIED**, original severities/4–7 roles,
unassigned actual DRIs, empty external receipts/approvals. Follow the
[operator readiness pack](../development/w3-production-release-approval-operator-readiness.md)
for real target/provider/operator/data/window/commands/stop/recovery authorization.
No cloud credentials, production settings, faults, backup restore or real soak are
used here. Existing governance planning choices are not deployment permissions.

## Read-only BUILD-02 verification limitation

The nominated Assessment SHA has three actual successful push-triggered runs:
[Repository validation](https://github.com/hhtuann/Quizopia_2.0/actions/runs/38538788388),
[Backend CI](https://github.com/hhtuann/Quizopia_2.0/actions/runs/38538788322) and
[W3 Production Release Validation](https://github.com/hhtuann/Quizopia_2.0/actions/runs/38538788360).
The classic `develop` branch-protection API returned **403 Resource not accessible
by integration**; the effective branch-rules endpoint was rejected by this connector
as an unsupported URL (400), not an empty policy result. Required-check/effective-rule
and repository-owner artifact acceptance therefore remain unverified. BUILD-02 is
NOT VERIFIED; settings were not changed and CI is not formal release approval.

## Exact next resumption actions

1. In the original checkout inspect status, branch/log and fetched develop; preserve
   all unfinished/unrelated changes. Resume `feature/w3m2-proctoring-livekit` with
   explicit M1/#89 → authority/#88 dependency. Do not reset/clean/rebase destructively.
2. Inspect actual MediaService/MediaProperties/JdbcMediaStore/provider/recovery,
   V2/OpenAPI and current report files. Finish any unresolved engineering defects. Inspect existing fresh PostgreSQL/HTTP/
   Gateway/owned-LiveKit reports and rerun affected checks only when changes, missing
   evidence or failures require it. Record actual command/count/result and exact
   tested source; do not substitute a historical count or invent completion.
3. Review public issuance default denial, current authority, own-grant binding,
   immutable fences, retry fairness, JWT redaction, V1/protected Assessment history,
   production closure and no changes to scores/answers/deadlines. Correct defects
   before committing; do not weaken provider/policy gates to obtain a green test.
4. Inspect the bounded M2 Draft PR/remote branch and exact-HEAD CI. If finalization
   is incomplete, finish only that same branch with the explicit #89 base; otherwise
   preserve its reviewable history and present independent review/Leader merge gates.
   Do not merge or force-push.
5. Separately scope the missing authoritative event adapter and real integrated
   topology/browser acceptance. Prepare actual provider feasibility/security and
   privacy decisions; request only the specific target authorization needed for
   later external exercises. Continue independent engineering while these are absent.

No artificial `plans/` task or completed milestone is created. Existing MVP/project
status records implementation progress and pending acceptance separately. Functional
M1/M2 delivery remains **PARTIAL**, production remains **NO-GO**, and no future
background execution or approval is claimed.
