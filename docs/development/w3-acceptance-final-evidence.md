# Wave 3 final acceptance evidence

**Issue:** [#97](https://github.com/hhtuann/Quizopia_2.0/issues/97)

**Recorded:** 2026-10-18 (Asia/Bangkok)

**Execution source:** `01e975d2c7639570986906e4cd2b2937a382198b`

**Work branch:** `test/w3-final-acceptance-97`

**Overall verdict:** **IN PROGRESS — ISSUE #97 IS NOT ELIGIBLE FOR CLOSURE**

**Production verdict:** **NO-GO**

This report distinguishes direct execution in the current workstream, historical
operator evidence, source inspection, and procedure-only material. It does not turn
a merge, local check, elapsed calendar time, or an unapproved provider operation
into acceptance evidence.

## A. Executive verdict

The merged Assessment and B3 implementation was rerun successfully from an exact
checkpoint whose parent is current `develop`. Six real Chromium journeys passed
through the actual Gateway, Identity, Quiz, Classroom, and Assessment JVMs with
dedicated synthetic PostgreSQL, Redis, and Mailpit resources. The run directly
reconfirmed the staged worker-OFF to worker-ON sequence, PREPARING/null-cutoff
behavior, exact server-issued 48-hour cutoff, recipient isolation, REQUEST/CANCEL
response-body equality and expected statuses, foreign-Teacher denial, cancellation
retention, and early-COMPLETE denial.

Issue #97 still cannot close. The following mandatory gates are not satisfied:

- explicit real-service coverage for every issue-level attempt/security scenario;
- monitoring-unavailable denial alongside a successful authoritative answer write;
- successful COMPLETE after a genuinely elapsed cutoff;
- a continuously eligible, independently reviewed soak lasting at least 48 hours;
- genuine multi-node failover, fencing, and acknowledged-write preservation;
- authorized real Chromium/LiveKit provider qualification and hard revocation;
- backup/restore, TLS, capacity, observability, privacy, operations, independent
  Security, and final Leader approval.

No mandatory executed gate failed in this workstream. Unavailable gates remain
`BLOCKED`; scenarios not covered by the current harness remain `NOT RUN`.

## B. Revision and environment manifest

### Repository provenance

| Item                              | Value                                                                                                         |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| Repository                        | `hhtuann/Quizopia_2.0`                                                                                        |
| Source branch at reconnaissance   | `develop`                                                                                                     |
| `develop` / accepted product base | `a9acb2264cf103948e44bf0cd01abd6bc2c6e1b2`                                                                    |
| Exact source SHA executed         | `01e975d2c7639570986906e4cd2b2937a382198b`                                                                    |
| PR #98 merge                      | `399e1fb1704daeb4e597bf901e03a48af5cd2689`                                                                    |
| PR #99 accepted head / merge      | `1dd011a9288239a7cd2a188a01e63b5880bcb9d3` / `e8bd838092b82a0de1ed652b1fce59ef019f2b32`                       |
| PR #100 accepted head / merge     | `edb27f2e54fd6ad61914ad690930880e5d15901a` / `a9acb2264cf103948e44bf0cd01abd6bc2c6e1b2`                       |
| Existing runtime branch           | `origin/test/w3-real-acceptance-runtime` is merged, 0 commits ahead and 7 behind `develop`; it was not reused |
| Work branch                       | `test/w3-final-acceptance-97`, created from current `develop`                                                 |

The service artifacts and Chromium run are pinned to `01e975d2...`. That checkpoint
has `a9acb226...` as its parent and adds only the sanitized evidence reporter, its
tests, and the preflight port-probe correction. The final report/receipt commit is a
documentation child of the executed checkpoint; it does not retroactively change
the executed service source.

GitHub checks for the accepted product base SHA were inspected on 2026-10-18:

- [Frontend CI push run `39047849902`](https://github.com/hhtuann/Quizopia_2.0/actions/runs/39047849902): **SUCCESS**.
- [Repository validation push run `39047849923`](https://github.com/hhtuann/Quizopia_2.0/actions/runs/39047849923): `compose-config` **SUCCESS** and
  `full-validation` **SUCCESS**.
- PR #100 `full-validation` was skipped by pull-request workflow design and is not
  described as passed.
- Effective branch-protection and required-check policy remains unverified, so these
  successful jobs do not close BUILD-02.

### Direct runtime identity

| Item                               | Value                                                    |
| ---------------------------------- | -------------------------------------------------------- |
| Fixture                            | `w3a-real-browser-v1`                                    |
| Runtime owner label                | `quizopia-w3a-7184182205`                                |
| Runtime state                      | `/tmp/quizopia-w3a-0wjoodus` (private, host-local state) |
| Runtime start / ready              | `2026-10-18T08:18:02Z` / `2026-10-18T08:18:55Z`          |
| Browser receipt interval           | `2026-10-18T08:19:07.393Z`–`2026-10-18T08:21:48.908Z`    |
| Verified cleanup                   | `2026-10-18T08:22:17Z` / `2026-10-18T15:22:17+07:00`     |
| Executor                           | Codex primary implementation agent in this workstream    |
| Named human staging/incident owner | **UNASSIGNED — BLOCKED**                                 |

The durable sanitized runtime manifest is
`docs/development/w3-real-acceptance-runtime-20261018.json` (SHA-256
`a3c529862e58963e4bbfa473b9cdd48474578c6429bae55adeeebf879dcdf53a`). It is a
projection of the launcher-owned post-teardown state and read-only cleanup checks;
it records the source SHA, owner label, topology, artifact hashes, timeline, staged
controls, browser-receipt hash, and zero remaining owned resources. The retained
private source state had SHA-256
`83d848a90fe9577fce31c5f40418b44ddce339f13a8f90e948a25e6922dc4760`.
The manifest is not independently signed and does not elevate this local run to an
approved staging or production receipt.

The topology used four independent PostgreSQL 17 containers, one Redis container,
one Mailpit container, four service JVMs, a Gateway JVM, and an exclusively started
Next.js/Chromium process. All service and dependency ports were loopback-only.
RabbitMQ and MinIO were excluded because none of the executed Assessment/B3 HTTP
paths use them. Proctoring and LiveKit were excluded because that separately gated
scope lacks authorization and unresolved product/privacy decisions remain.

Sanitized service endpoints:

| Component   | Endpoint                 |
| ----------- | ------------------------ |
| Identity    | `http://127.0.0.1:40117` |
| Quiz        | `http://127.0.0.1:40297` |
| Classroom   | `http://127.0.0.1:57533` |
| Assessment  | `http://127.0.0.1:60013` |
| Gateway     | `http://127.0.0.1:8080`  |
| Mailpit API | `http://127.0.0.1:52125` |
| Frontend    | `http://localhost:3000`  |

Artifact SHA-256 digests:

| Artifact   | SHA-256                                                            |
| ---------- | ------------------------------------------------------------------ |
| Identity   | `d2f5c8ff807632c91726014e3e791b826631f6112b2d090cdaa6f0f43e87133d` |
| Quiz       | `7dbdedf2904395542bfa05f2f4220a6ef2af01c9b669a24ce643138d770e7cf2` |
| Classroom  | `42d1d7d2847c4d587dae6305d25211d0809d510752a94c142b1b99c9a2ad7e3e` |
| Assessment | `5318ebe16030dc2390bec844aef7addd052076ba80e2d5c34e8e1b80ea096bed` |
| Gateway    | `7a605fbe34d2696e7043d992a3e86ecbda6f557435fa4cdbf01be1d99856992f` |

Execution toolchain:

| Tool                 | Version                            |
| -------------------- | ---------------------------------- |
| Java                 | OpenJDK `21.0.12.1`                |
| Docker client/server | `29.9.0` / `29.9.0`                |
| Node.js              | `24.21.0`                          |
| pnpm                 | `10.15.1`                          |
| Playwright           | `1.62.1`                           |
| Chromium             | Chrome for Testing `151.0.7922.34` |
| PostgreSQL image     | `postgres:17-alpine`               |
| Redis image          | `redis:8.2-alpine`                 |
| Mailpit image        | `axllent/mailpit:v1.27.8`          |

The bounded test enabled only the Assessment/Identity controls required for the
synthetic journey. B3 Assessment edge, recipient, and worker controls started OFF,
were enabled in two explicit stages, and existed only in the owned runtime.
Monitoring capture/publishing, Proctoring, and media stayed disabled. Committed
production defaults remain disabled.

## C. Acceptance matrix

| Gate                                    | Required evidence                                                                                                 | Result      | Evidence reference                                                                             | Remaining action                                                                 |
| --------------------------------------- | ----------------------------------------------------------------------------------------------------------------- | ----------- | ---------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| Assessment/B3 test-topology isolation   | Read-only preflight, owned resources, unique ports, scoped teardown                                               | **PASS**    | Direct run and sanitized runtime manifest; `cleanup_unverified=[]`                             | Does not establish full staging or provider topology                             |
| Named human operator                    | Accountable environment, incident, handoff, and cleanup ownership                                                 | **BLOCKED** | Actual release-gate DRIs remain unassigned                                                     | Appoint and evidence authorized human operators and backup coverage              |
| Real Assessment E2E — executed scope    | Real Gateway/Chromium PUBLIC + CLASS, four types, autosave, submit/abandon, results, direct close/review          | **PASS**    | Direct 6/6 run in section D                                                                    | None for the exact executed scope                                                |
| Full issue-level attempt lifecycle      | Explicit START, RESUME, RETAKE, stale answer conflict, submit replay, refresh persistence                         | **NOT RUN** | Harness inspection; not all are explicit in the six receipts                                   | Add and execute real Gateway/browser/API assertions                              |
| Security journeys — executed scope      | Membership denial, owner/role isolation, account switch, recipient masking, foreign Teacher                       | **PASS**    | Direct 6/6 run                                                                                 | None for exact executed cases                                                    |
| Remaining security checklist            | Foreign-Student attempt/result, terminal-state matrix, explicit pre-CLOSED key nondisclosure, stale authorization | **NOT RUN** | No complete direct receipt found                                                               | Add and execute server-side assertions                                           |
| Monitoring unavailable vs answer writes | Real monitoring denial plus successful authoritative answer save                                                  | **NOT RUN** | Proctoring stayed disabled                                                                     | Add a real-service failure-path journey proving denial and successful SAVE       |
| B3 staged readiness                     | Worker OFF, preflight, edge activation, PENDING/null cutoff, then worker ON                                       | **PASS**    | Direct B3 journey                                                                              | None for this subgate                                                            |
| B3 pre-cutoff lifecycle                 | REQUEST, PREPARING, confirmation, +48h cutoff, early COMPLETE denial, CANCEL                                      | **PASS**    | Direct B3 journey                                                                              | Complete lifecycle remains open until genuine post-cutoff COMPLETE               |
| Idempotency body/status behavior        | Same-key/payload replay, full body equality, and conflicting/new-key behavior                                     | **PASS**    | Direct REQUEST/CANCEL B3 assertions                                                            | None for these assertions                                                        |
| Idempotency headers/durable duplication | Relevant response headers and direct duplicate transition/notice/side-effect counts                               | **NOT RUN** | Not captured by the current harness                                                            | Extend assertions and rerun                                                      |
| Recipient isolation                     | Owner/ex-member access, foreign recipient masking, foreign Teacher denial                                         | **PASS**    | Direct B3 journey                                                                              | None for the executed policy                                                     |
| Post-cutoff COMPLETE                    | Real elapsed cutoff and durable finalization/closure/review                                                       | **BLOCKED** | Early denial only                                                                              | Complete valid ≥48h staging run, then execute authenticated Teacher COMPLETE     |
| 48-hour soak / NOTICE-04                | Approved continuous-observation criteria and ≥48 real elapsed hours                                               | **NOT RUN** | Formal register status remains NOT VERIFIED; no qualifying run or waiver                       | Approve criteria, environment, operator, telemetry, and run a new full window    |
| Multi-node failover                     | Approved multi-node partition/promotion/fencing and integrity evidence                                            | **BLOCKED** | Only single-node pause/restart evidence exists                                                 | Select/provision approved HA topology and authorize fault drill                  |
| W3-M Activity Evidence E2E              | Current-head Chromium, authoritative Assessment integration, RabbitMQ delivery, and outage behavior               | **NOT RUN** | Historical backend/integration evidence is not issue-qualified current-head browser acceptance | Execute authorized monitoring-unavailable and recovery journeys                  |
| LiveKit browser media                   | Authorized real camera publish and Teacher subscribe-only                                                         | **BLOCKED** | No authorized browser-camera or provider-qualification execution                               | Resolve PROCTOR-01/07/11/12 and obtain Privacy/Security/Operations authorization |
| LiveKit negative/lifecycle matrix       | Permission denial, reconnect, outage, stale/expired grants, refresh, audience/role/media denials                  | **BLOCKED** | Provider execution is not authorized                                                           | Include every negative in the approved qualification plan                        |
| Provider hard revocation                | Provider-enforced original/refreshed-token cutoff                                                                 | **BLOCKED** | Backend/local disconnect is insufficient                                                       | Select provider and perform authorized qualification                             |
| Privacy/security/governance             | Current independent decisions and approvals                                                                       | **BLOCKED** | Release register has no external receipts/approvals                                            | Appoint accountable humans and independent reviewers; record actual decisions    |
| Backup/restore and operations           | Authorized target, encrypted backup, isolated restore, RPO/RTO/integrity review                                   | **BLOCKED** | Historical local dump/restore passed; operational runbook remains procedure-only               | Bind approved target/operator commands and execute                               |
| DB-05 migration rehearsal               | Approved target-sized fresh/upgrade migration with lock/WAL/space/invariant evidence                              | **NOT RUN** | Formal register status remains NOT VERIFIED                                                    | Approve target dataset/thresholds and execute the rehearsal                      |
| Feature defaults                        | Source defaults remain disabled                                                                                   | **PASS**    | Gateway/Assessment/Proctoring config inspection                                                | Verify effective staging config once provisioned                                 |
| Effective deployed defaults             | Sanitized target configuration fingerprint                                                                        | **BLOCKED** | No approved deployment exists                                                                  | Provision authorized staging and capture effective config                        |
| Observed exact-SHA workflows            | Push Frontend CI, compose-config, full-validation                                                                 | **PASS**    | GitHub runs `39047849902`, `39047849923`                                                       | Formal BUILD-02 remains NOT VERIFIED                                             |
| BUILD-02 / effective check policy       | Approved RC/artifacts, effective required checks/protection, and independent approval                             | **NOT RUN** | Formal register status remains NOT VERIFIED; release candidate SHA is null                     | Bind and approve the exact release candidate and effective policy                |
| Teardown                                | Owned processes/containers gone; unrelated developer resources unchanged                                          | **PASS**    | Sanitized runtime manifest: stopped `08:22:17Z`, zero remaining/unverified owned resources     | None                                                                             |
| Final evidence provenance/review        | Committed report revision, durable sanitized receipt/integrity reference, and independent acceptance              | **BLOCKED** | Hashed JSONL and runtime manifest are included; independent acceptance is absent               | Review the package independently and record an accountable acceptance decision   |
| Leader closure authorization            | Explicit final decision after all mandatory gates pass                                                            | **BLOCKED** | No such approval exists                                                                        | Keep issue #97 open                                                              |

The table above classifies this workstream's evidence. It does not replace the
authoritative production register. The formal eleven-gate overlay remains exactly:

| Formal gate | Authoritative status |
| ----------- | -------------------- |
| BUILD-02    | **NOT VERIFIED**     |
| NOTICE-04   | **NOT VERIFIED**     |
| RECOVERY-05 | **BLOCKED**          |
| DB-05       | **NOT VERIFIED**     |
| DB-08       | **BLOCKED**          |
| TLS-02      | **BLOCKED**          |
| CAPACITY-04 | **NOT VERIFIED**     |
| OBSERVE-02  | **BLOCKED**          |
| PRIVACY-02  | **BLOCKED**          |
| DEPLOY-02   | **NOT VERIFIED**     |
| APPROVAL-01 | **BLOCKED**          |

## D. Direct execution receipts

### Host preflight

Command:

```bash
python3 scripts/local/w3-real-acceptance-preflight.py
python3 -m unittest discover -s scripts/local/tests \
  -p 'test_w3_real_acceptance_preflight.py' -v
```

Observed result: host prerequisites available; Docker, Java 21, Node, pnpm,
OpenSSL, bcrypt, and repository/runtime files present. The safety suite passed
5/5 after correcting a `TIME_WAIT` false positive in the port probe. A successful
preflight is not runtime, provider, or release acceptance.

Chronology: the first current-base runtime completed and cleaned up at
`2026-10-18T02:43:04Z`. An immediate repeat preflight then reported port 3000 as
occupied although no listener existed; inspection identified a recently closed
reusable listener/TIME_WAIT mismatch. The probe was corrected to use the same
IPv4 reusable-bind semantics and `listen(1)`, its focused test passed 5/5, the full
local script suite passed 34/34, and the fresh checkpoint preflight above passed at
`2026-10-18T08:18:02Z`.

### Runtime provision

Command:

```bash
W3_BACKEND_SHA=01e975d2c7639570986906e4cd2b2937a382198b \
W3A_IDENTITY_NOTICE_RECIPIENTS_ENABLED=true \
W3A_ASSESSMENT_CLOSURE_WORKER_ENABLED=false \
W3A_ASSESSMENT_EDGE_INBOX_VERIFIED=false \
W3A_ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED=false \
W3A_REAL_FRONTEND_URL=http://localhost:3000 \
python3 scripts/local/w3a-real-runtime.py up
```

Observed result: five exact-source artifacts built; four service JVMs plus the
Gateway JVM became ready; worker/edge attestation started OFF; monitoring/media
were disabled.

### Real Chromium acceptance

Command:

```bash
cd frontend
W3A_REAL_STATE_DIRECTORY=/tmp/quizopia-w3a-0wjoodus \
W3A_REAL_FRONTEND_URL=http://localhost:3000 \
W3A_REAL_EVIDENCE_FILE="$PWD/../docs/development/w3-real-acceptance-20261018.jsonl" \
W3A_REAL_SOURCE_SHA=01e975d2c7639570986906e4cd2b2937a382198b \
W3A_REAL_EVIDENCE_RUN_ID=w3-final-20261018 \
pnpm test:e2e:real:w3
```

Observed result: **6/6 journeys passed**, with no Playwright Gateway request
interception and no trace, screenshot, or video capture.

Key server/runtime observations:

- PUBLIC: four question types saved through real services; attempt submitted;
  authoritative 100.00% result shown; Teacher closed; owner review became available.
- CLASS: pre-membership entry denied; active attempt blocked CLOSE; Student abandon
  allowed durable close; authenticated inbox returned 200.
- B3: pre-activation REQUEST returned 503; edge activated while worker stayed OFF;
  request became PENDING with null cutoff and empty inbox; worker activation exposed
  PREPARING/null timestamps before confirmation.
- Confirmed notice `8428b585-b598-4fd2-ab79-c3655b7cf9d2` had
  `confirmedNoticeAt=2026-10-18T08:20:55.911689Z` and
  `closureCutoffAt=2026-10-20T08:20:55.911689Z`, an exact 172,800,000 ms delta.
- Former member retained own notice access while new assessment entry was denied;
  foreign recipient was masked with 404 and had an empty inbox.
- Early COMPLETE returned 409 and preserved the PENDING request/OPEN Publication.
- Foreign Teacher received 404 across GET/REQUEST/CANCEL/COMPLETE.
- REQUEST same-key replay returned an identical JSON body with 201; altered payload
  returned `IDEMPOTENCY_CONFLICT`; a new key returned `CLOSURE_PENDING`.
- CANCEL same-key replay returned an identical JSON body with 200; altered payload
  returned `IDEMPOTENCY_CONFLICT`; a new key returned `CLOSURE_RESOLVED`; Student
  cancellation returned 403; the cancelled notice remained durable.
- Owned dependency pauses exercised 503/recovery and lost-response reconciliation
  without logging credentials or private locators.

The durable sanitized receipt is
`docs/development/w3-real-acceptance-20261018.jsonl` (mode `0600` when generated,
11,498 bytes, SHA-256
`4c2adedaa9ebb928353703a80902d10e02f48427b404018ba37d4ad62b6a061b`). It contains
exactly one run-start record, six passed test-result records, and one passed run-end
record. Every record names source SHA `01e975d2...` and run ID
`w3-final-20261018`; a value-pattern scan found no bearer credentials, passwords,
authorization values, access/refresh tokens, or OTP values. Failure payloads are
excluded by the reporter.

The companion sanitized runtime manifest and its hash are recorded in section B.
It links that browser receipt to the launcher-owned source/artifact identity,
staged controls, readiness interval, and post-teardown zero-resource observation.
Its explicit limitations preserve the distinction between direct local evidence
and independent release approval.

### Focused regression and governance checks

```text
34/34  python3 -m unittest discover -s scripts/local/tests -p 'test_*.py'
685/685 frontend Vitest suite (57/57 files)
8/8    frontend/tests/w3a-real-config.test.ts
3/3    frontend/tests/w3a-real-sanitized-reporter.test.ts
60/60  services/assessment-service/contracts/test-w3-production-release.py
PASS   validate-w3-production-release.py (6 BLOCKED / 5 NOT VERIFIED preserved)
PASS   frontend lint, Prettier check, and TypeScript typecheck
```

### Teardown

Command:

```bash
python3 scripts/local/w3a-real-runtime.py down /tmp/quizopia-w3a-0wjoodus
```

Observed result: `Owned runtime cleanup complete`; `cleanup_unverified=[]`; all six
owned containers and five service processes were removed/stopped. The default
developer PostgreSQL, Redis, RabbitMQ, Mailpit, MinIO, and LiveKit containers remained
healthy. No process was signalled on ambiguous ownership.

## E. Blocker register

| Gate                             | Type                       | Exact prerequisite                                                                                         | Accountable role                           | Evidence required to unblock                                                         |
| -------------------------------- | -------------------------- | ---------------------------------------------------------------------------------------------------------- | ------------------------------------------ | ------------------------------------------------------------------------------------ |
| Full Assessment checklist        | Technical                  | Add missing issue-level real tests                                                                         | Primary implementation owner               | Fresh exact-SHA HTTP/browser and durable-state receipts                              |
| Human operator ownership         | Governance                 | Named environment, incident, handoff, and cleanup operators with backup coverage                           | Leader + Operations                        | Appointment/authorization references and accepted coverage                           |
| BUILD-02                         | Governance + CI            | Approved RC/artifacts and effective required-check/protection policy                                       | Repository owner + Leader                  | Exact-SHA policy/CI evidence and independent approval                                |
| NOTICE-04 / post-cutoff COMPLETE | Temporal + governance      | Approved strict-continuity criteria, authorized staging, named operator/incident owner                     | Leader + Operations + independent Security | Genuine ≥48h timeline, complete telemetry, post-cutoff COMPLETE/CLOSED review        |
| RECOVERY-05                      | Environmental + governance | Selected HA provider/topology, accepted failure model and fault authorization                              | Platform/SRE/DBA + Security                | Partition/fencing/promotion receipts and before/after acknowledged-write manifests   |
| DB-05                            | Environmental + governance | Approved target-sized dataset, thresholds, drain window, and migration operator                            | DBA/Platform + Backend                     | Fresh/upgrade migration timing, lock/WAL/space, invariant, and roll-forward evidence |
| DB-08                            | Environmental + governance | Approved encrypted backup/restore target and RTO/RPO                                                       | DBA/SRE + Security                         | Isolated restore and integrity/reconciliation evidence                               |
| TLS-02                           | Environmental              | Actual private DNS/certificates/trust and exact clients/scopes                                             | Platform/DevSecOps                         | Positive and wrong-cert/host/client/scope tests                                      |
| CAPACITY-04                      | Product + environmental    | Approved workload profile and quantitative criteria                                                        | Backend/Performance/Platform               | Repeated target-like 100-active-Student measurements                                 |
| OBSERVE-02                       | Operations                 | Deployed restricted sinks, thresholds, responders, incident drill                                          | SRE/Operations + Security                  | Delivered alert and redaction/runbook receipts                                       |
| PRIVACY-02 / LiveKit             | Product + governance       | Resolve PROCTOR-01/07/11/12; approve lawful basis, safeguards, retention, deletion, location, participants | Leader + qualified Privacy/Security        | Current signed decisions and control-parity evidence                                 |
| W3-M Activity Evidence           | Technical + governance     | Approved current-head browser/RabbitMQ/Assessment outage and recovery scenario                             | Backend + Operations + Security            | Real end-to-end monitoring-denial/write-isolation receipts                           |
| Provider qualification           | External + governance      | Select provider and authorize bounded synthetic media test                                                 | Privacy/Security/Operations                | Browser/provider publish/subscription/outage/revocation evidence                     |
| DEPLOY-02                        | Environmental + governance | Approved RC, images/config, staging target, operators, commands and rollback                               | Platform/SRE/Backend                       | Executed deployment/disable/roll-forward receipts                                    |
| APPROVAL-01                      | Governance                 | All mandatory gates dispositioned, outside-team Security review, explicit Leader GO                        | Independent Security + Leader              | Exact-scope approvals, incident/rollback owners                                      |

The existing production register remains authoritative for the formal eleven-gate
overlay: **6 BLOCKED, 5 NOT VERIFIED, release candidate SHA null, and PRODUCTION
NO-GO**. This report does not amend those states.

## F. Reproduction and next executable actions

The preflight, provision, Chromium, and teardown commands above reproduce only the
bounded synthetic Assessment/B3 run. Always set `W3_BACKEND_SHA` explicitly; do not
rely on the historical launcher default. Inspect existing listeners and Docker
resources before provision, and execute `down` only against the exact private state
directory printed by `up`.

The genuine soak, failover, backup/restore, LiveKit, and provider commands are
deliberately not invented. Before any such operation, the responsible humans must:

1. assign a named environment/operator, backup coverage, incident owner, and cleanup owner;
2. approve the exact source/artifacts/configuration and private staging topology;
3. freeze pass/fail, interruption, telemetry-completeness, and stop/recovery criteria;
4. authorize synthetic actors/data, provider/media scope, evidence storage, retention,
   and deletion;
5. bind reviewed provider-specific commands to verified targets;
6. preserve a start receipt before the operation and obtain independent review after it.

Until those prerequisites exist, the next safe engineering action is to extend the
real Assessment harness for the `NOT RUN` rows. The next governance action is the
formal owner/criteria/authorization package described in
`w3-production-release-approval-operator-readiness.md`.

## G. Draft issue #97 update — do not publish before human review

This text is a handoff draft only. It has not been posted to GitHub.

> Exact-checkpoint direct rerun completed on
> `01e975d2c7639570986906e4cd2b2937a382198b` (parent/current accepted product
> base `a9acb2264cf103948e44bf0cd01abd6bc2c6e1b2`):
> host preflight passed, 6/6 real Gateway/Chromium Assessment+B3 journeys passed,
> a durable sanitized receipt was retained and hashed, and owned teardown completed
> with zero unverified resources. Product-base push
> Frontend CI, compose-config, and full-validation are successful. This reconfirms
> the implemented synthetic scope only. Explicit remaining Assessment scenarios,
> monitoring-unavailable/write coexistence, genuine ≥48-hour soak and post-cutoff
> COMPLETE, multi-node failover, LiveKit/provider qualification, backup/restore,
> privacy/security/operations approvals, and final Leader authorization remain
> NOT RUN or BLOCKED. Production defaults remain disabled. Issue #97 is not eligible
> for closure and production remains NO-GO. See
> `docs/development/w3-acceptance-final-evidence.md`.
