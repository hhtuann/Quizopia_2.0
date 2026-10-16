# W3 real-runtime acceptance — isolated operator preflight

**Status: PREPARATION ONLY.** Issue [#97](https://github.com/hhtuann/Quizopia_2.0/issues/97) remains **BLOCKED / NOT VERIFIED**. This document and its helper do **not** run a real Gateway, Chromium or LiveKit integration test. They do not grant release/privacy/provider authorization.

## What already exists

- `scripts/local/w3a-real-runtime.py`: historical five-process, isolated W3-A harness, including Identity, Quiz, Classroom, Assessment, Gateway and dedicated test PostgreSQL/Redis/Mailpit. It **does not** qualify W3-B/C/B3 Student lifecycle, closure/inbox or W3-M Proctoring/LiveKit.
- `frontend/tests/w3a-real-e2e/publication.spec.ts`: real Gateway/Chromium Publication-only verification.
- `frontend/tests/e2e/w3-final-integration-journeys.spec.ts`: 76-suite baseline's intercepted-Gateway browser tests; **not real-service evidence**.
- `docker-compose.yml`: infrastructure-only Compose file, not a complete six-service harness. Defaults may already be in use by another developer and **must not be shared or stopped**.
- `docs/development/w3-production-evidence-handoff.md` and `docs/development/w3-production-evidence-register.yaml`: production operator/approval gates remain open.

## Read-only host preflight

In a clean checkout of the latest `develop` (or this task branch):

```bash
python3 scripts/local/w3-real-acceptance-preflight.py
python3 -m unittest discover -s scripts/local/tests -p 'test_w3_real_acceptance_preflight.py' -v
```

The preflight outputs JSON: `git_commit`, available binaries, Docker daemon reachability, historical harness files and loopback 3000/8080 port observations.

- Exit `0`: **host prerequisites available only**; does *not* imply isolated topology, credentials, execution authorization or acceptance.
- Exit `2`: prerequisites blocked; fix missing tools or owned runtime before any test attempt.
- `legacy_port_8080_free: false`: current W3-A launcher binds 8080 and cannot be used safely as-is. Never kill an unknown listener. A new launcher should use allocated unique loopback ports throughout.
- `legacy_port_3000_free: false`: an existing frontend may belong to someone else; never silently reuse it.
- Do **not** attach secrets, private env files, tokens, full logs or camera footage to the PR or issue.

## Execution sequence for the primary acceptance agent/operator

1. Pin the exact `develop` commit; inspect `AGENTS.md`, service configs, OpenAPI, and all existing W3-A/LD-05 runtime safety controls. Preserve other developer resources.
2. Prepare a **distinctly named, ownership-labeled, dedicated, loopback-only** test topology (unique DBs/DB credentials, Redis, RabbitMQ, Mailpit, MinIO, JVM services, frontend ports; LiveKit separately when authorized). Generate ephemeral secrets and store them only under a private temporary directory; cleanup removes only resources recorded as owned by that run.
3. Run an initial **Assessment-only** real-browser acceptance using actual authenticated Gateway, Identity, Quiz, Classroom, Assessment. Exercise teacher OPEN, PUBLIC/CLASS Student entry, four types of answer save, revisions/retries, submit, result and CLOSED review. Log only sanitized HTTP outcomes, service SHAs and timestamped fixture IDs.
4. Extend to **closure/inbox** only with verified actual recipient-accessibility and documented test readiness; never invent confirmation or shorten a 48-hour production cutoff. Observe genuine 48-hour operational soak separately under approved staging protocols.
5. Treat **W3-M/LiveKit real media** as a separate conditional gate. Do not enable monitoring/media or supply fake `PROCTORING_PRIVACY_APPROVAL_REFERENCE`, `PROCTORING_RETENTION_POLICY_REFERENCE`, or `PROCTORING_MEDIA_QUALIFICATION_REFERENCE`. Obtain valid scoped test authorization, correct retention references and provider qualification first. Then test actual camera publish / Teacher subscribe, denied permission, disconnect and provider revocation on an isolated service/provider topology.
6. For every real run: evidence must include commit/service image identifiers, exact tested workflow, environment and port ownership, fixture flags, sanitized server responses, actual browser/provider receipts, outcome, cleanup verification and every remaining blocker. Compare real vs intercepted Playwright explicitly.

## Separate verdicts — do not collapse

| Gate | Status before real execution |
| --- | --- |
| Frontend Wave 3 composition / mocked browser suite | PASS (merged PR #96) |
| Safety preflight on the real developer host | NOT RUN |
| Real Assessment/Identity/Classroom/Quiz/Gateway HTTP + Chromium | BLOCKED / NOT VERIFIED |
| Real closure/inbox and per-recipient confirmation | BLOCKED / NOT VERIFIED |
| Real W3-M Activity Evidence + LiveKit publish/subscription/provider failure | BLOCKED / NOT VERIFIED |
| Privacy/Security/provider signoff, 48-hour soak, production release | OPEN / NO-GO |

A preflight PASS is **never** a substitute for real HTTP, media, reviewer/operator appointments or production authorization.

## Primary Codex task

After the operator confirms an exclusive disposable test environment, use ONE primary Codex agent to extend the existing owned W3-A launcher and real E2E tests incrementally, keeping the initial Assessment-only run independent of monitoring. Run only accepted synthetic fixtures, use targeted tests during development, capture actual (not mocked) results, stop at unapproved governance gates, run full validation at final HEAD and open a Draft PR tied to issue #97. Do not merge an unverified real-acceptance implementation.
