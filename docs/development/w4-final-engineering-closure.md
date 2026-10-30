# Wave 4 — Engineering Closure & Final Integration Evidence (2026-10-30)

> **Subsequent PO reopening (2026-10-30):** this historical engineering closure
> does not close final product acceptance. [W4F-T1 #133](https://github.com/hhtuann/Quizopia_2.0/issues/133)
> starts a four-task sequential completion program. Only T1 is authorized; see
> [contract/approval matrix](../specifications/w4f-assessment-completion-contract.md)
> and [T1 delivery record](w4f-t1-delivery.md). Privacy/retention/security approvals
> and production NO-GO remain independent gates.

> **Disposition:** **WAVE 4 ENGINEERING CLOSED** (implementation, required PR integration, local/disposable acceptance evidence); **PRODUCTION NO-GO** (external release acceptance is separate). The final post-merge develop CI verdict is recorded below and must be checked before treating the merged checkpoint as an accepted CI release candidate.
>
> **Verified code baseline:** `develop` `cac7002e597643fd62239f5504df43a49570db54` (merge PR #125).
>
> **Scope boundary:** this document closes Wave 4 *engineering delivery*, including the W4 realtime milestones and the subsequently completed UX/FB-015 integration workstreams. It does **not** certify staging/production deployment, operational service levels, actual media-provider erasure, all backup copies erased or legal compliance. [W4 release readiness issue #129](https://github.com/hhtuann/Quizopia_2.0/issues/129) owns those future approvals.

## 1. Final merged evidence (all verified against GitHub)

### Wave 4 Realtime core

| Milestone | PR | Merge SHA | Implemented |
| --- | --- | --- | --- |
| W4-B1 | [#103](https://github.com/hhtuann/Quizopia_2.0/pull/103) | `2466e20466ab39ca07cd9d47a043b796badb11c0` | Gateway realtime ticket, AUTH, Origin and admission |
| W4-B2 | [#104](https://github.com/hhtuann/Quizopia_2.0/pull/104) | `2676ee80d18fc648e3139725f65d8962cfd05efd` | Assessment subscription authorization |
| W4-B3 | [#105](https://github.com/hhtuann/Quizopia_2.0/pull/105) | `d87a3b37b12d125d0586bd38a565ae0192337dca` | Durable outbox, RabbitMQ, Gateway invalidation |
| W4-B4 | [#106](https://github.com/hhtuann/Quizopia_2.0/pull/106) | `cad2d25c73232653e65f037b5ebf71d076b2b42d` | Frontend WebSocket + authoritative REST reconciliation |
| W4-E | [#107](https://github.com/hhtuann/Quizopia_2.0/pull/107) | `2d837e5f853cc2c929d8433e62b2da3f07a60cd2` | Realtime integration/acceptance, 15/15 local/test journeys |

See [W4 Realtime Acceptance Evidence](w4-acceptance-evidence.md). Its 14 protocol journeys and one real-Chromium journey ran on disposable services/containers; this is not a production acceptance result.

### Post-W4 core product completion, UX and destructive-operation hardening

| Area | Merged PRs | Verified outcomes |
| --- | --- | --- |
| Student Attempt and editor UX | [#114](https://github.com/hhtuann/Quizopia_2.0/pull/114) | Focused Attempt workspace, flags, language-menu consolidation, NUMERIC_FILL autocomplete, geometry fixes |
| Round 3 Assessment UX | [#117](https://github.com/hhtuann/Quizopia_2.0/pull/117), [#116](https://github.com/hhtuann/Quizopia_2.0/pull/116) | Create-on-first-valid-save, publication/results-first, compact locator/actions, natural vi/en microcopy |
| FB-012 Student Display Names | [#119](https://github.com/hhtuann/Quizopia_2.0/pull/119) | Owner-authorized bounded Identity→Assessment profile lookup and results UI, default-OFF gates |
| BR-01 product resolution | [#127](https://github.com/hhtuann/Quizopia_2.0/pull/127), [#128](https://github.com/hhtuann/Quizopia_2.0/pull/128) | Any current STUDENT may attempt eligible PUBLIC, including their own; CLASS requires actual Student roster membership; no owner exclusion or mandatory Teacher Preview |
| FB-015 contract and foundation | [#121](https://github.com/hhtuann/Quizopia_2.0/pull/121), [#122](https://github.com/hhtuann/Quizopia_2.0/pull/122), [#123](https://github.com/hhtuann/Quizopia_2.0/pull/123) | ADR-018 permanent erasure, durable deletion request/idempotency, Quiz authoring fence, Assessment authoritative revocation |
| FB-015 purge and frontend | [#124](https://github.com/hhtuann/Quizopia_2.0/pull/124), [#125](https://github.com/hhtuann/Quizopia_2.0/pull/125) | Least-privilege, bounded restart-safe dependent purge; migration fail-closed role provisioning; legacy Proctoring dependents; user confirmation and status recovery |

Final FB-015 merges: #124 `bd7c5b8a308d8e4b7fef6c9f57c48da6a106ecee`; #125 `cac7002e597643fd62239f5504df43a49570db54`. Neither PR was auto-merged. Both were inspected independently for H1–H5 plus the later migration-credential blocker before merge.

## 2. Acceptance and evidence classification

| Verification | Verdict / boundary | Source |
| --- | --- | --- |
| W4 realtime integrated journeys | **15/15 PASS on disposable local/test topology**, including real Chromium; historical W4-E milestone | [W4-E acceptance](w4-acceptance-evidence.md) |
| FB-015 contract/security review | H1–H5 and fixed-password Flyway fallback corrected; forbidden privileges/migrations negative-tested in CI | [Correction regression](fb015-correction-regression.md), [operations runbook](fb015-deletion-operations-runbook.md) |
| FB-015 real Browser→Gateway→services | Agent-reported **PASS** for isolated synthetic backend-fetch and real Next.js/Chromium journeys, with PostgreSQL/Redis and authentic Identity issuer. **Not independently re-run by the Leader on the user's runtime.** Provider receipt in this harness is **synthetic custodian evidence**, not LiveKit certification. | [Disposable harness](../../scripts/local/fb015-acceptance.md); [overnight handoff](fb015-overnight-handoff.md) |
| PR #124 HEAD CI | Backend CI 8/8 module jobs **SUCCESS**; repository validation **SUCCESS** on reviewed `030d3e066d12b21a1a3b3decbe378199e19b233e` | [Backend run](https://github.com/hhtuann/Quizopia_2.0/actions/runs/40992196468) |
| PR #125 HEAD CI | Backend CI 8/8 **SUCCESS**, Frontend CI **SUCCESS**, repository validation **SUCCESS** on reviewed `e661a68e2dc6ce1553b1e3d701b49eb07f2f5f4a` | [Backend](https://github.com/hhtuann/Quizopia_2.0/actions/runs/40992237539) · [Frontend](https://github.com/hhtuann/Quizopia_2.0/actions/runs/40992237581) · [Repository](https://github.com/hhtuann/Quizopia_2.0/actions/runs/40992237523) |
| **Merged develop push CI** | **PARTIALLY VERIFIED: Backend CI 8/8 PASS, Frontend CI PASS; Repository full-validation IN PROGRESS**. Workflows were started for exact merged SHA `cac7002e597643fd62239f5504df43a49570db54`, not to be confused with pre-merge branch CI. | [Backend](https://github.com/hhtuann/Quizopia_2.0/actions/runs/40997003210) · [Frontend](https://github.com/hhtuann/Quizopia_2.0/actions/runs/40997003301) · [Repository/full-validation](https://github.com/hhtuann/Quizopia_2.0/actions/runs/40997003143) |
| Full production environment, legal/provider/backup evidence | **NOT TESTED / NOT APPROVED**. A passing synthetic Testcontainers scenario is not a real-provider or approved staging/production rehearsal. | [Release gates](w3-production-release-gates.md) · [W4 release tracker #129](https://github.com/hhtuann/Quizopia_2.0/issues/129) |

This document does not invent a passing `develop` CI or new local test execution. If the post-merge job fails, fix the defect on a new branch and update this closure record. Do not classify a failing required job as environmental without evidence.

## 3. FB-015 semantics and safety boundaries

- Approved product semantics: **permanently erase eligible owned Quiz, QuizVersion, Publication, Attempt, answer, score/result, monitoring and dependent records**; keep unrelated Identity accounts, Classroom rosters and other Quizzes. Preservation laws/holds are mandatory exceptions.
- Two-stage barrier: Quiz source/authoring fence → confirmed Assessment/Proctoring revocation → service-owned, bounded, restart-safe, FK-safe hard purge. Post-fence admissions, cached/replayed results and monitoring denied authoritatively.
- Assessment V11/Proctoring V5 production Flyway migrations **do not create LOGIN roles or passwords**. Required owner/runtime/custodian roles must already be provisioned; missing/misconfigured grants fail closed. Disposable role fixtures live under `src/test/resources`; `infrastructure/postgres/01-init-databases.sh` is local-development infrastructure, **not** a production role-provisioning method.
- Frontend requires explicit confirmation; titleless Quiz is handled. Unknown-commit retries reconcile using server-owned latest-operation status and stable idempotency. Student access denial is a server gate, not a UI-only removal.
- The implemented maximum truthfully reported milestone is `PRIMARY_PURGED` with `COPY_EXPIRY_PENDING` where relevant. **`ALL_COPIES_ERASED` is intentionally unreachable** until verified provider/backup expiry or destruction. Test-only synthetic receipts do not qualify.
- `QUIZ_DELETION_ENABLED`, `ASSESSMENT_DELETION_ENABLED`, `PROCTORING_DELETION_ENABLED` remain default **OFF**. No real student data has been deliberately erased under this milestone.

## 4. What “Wave 4 CLOSED” means

| Gate | Final disposition |
| --- | --- |
| W4-B1/B2/B3/B4/E implementation and merged contracts | **CLOSED** |
| W4-E bounded disposable local/browser acceptance | **PASS** |
| UX/FB-012/FB-015 follow-up feature PR integration | **CLOSED — all listed PRs merged** |
| FB-015 destructive test-only functional verification | **PASS as reported with artifact/repo evidence; not a new Leader-run local acceptance** |
| `develop` push CI and full-validation | **MUST BE VERIFIED at exact SHA before recording release-candidate CI acceptance** |
| Regulatory data retention / provider-side deletion / DR / TLS / HA / performance | **OPEN in release governance** |
| Production features/deployment / user-data destructive operations | **NO-GO / NOT AUTHORIZED** |

Wave closure is not a production release declaration. Legacy W3 production gate issue [#97](https://github.com/hhtuann/Quizopia_2.0/issues/97) and [W3 release register](w3-production-release-gates.md) remain unchanged and require independent authorization. [W4 release readiness #129](https://github.com/hhtuann/Quizopia_2.0/issues/129) tracks the added W4/FB-015 operator and legal gates. No release candidate, SLO, data-retention period, custodian approval or real provider receipt is invented.

## 5. Handoff to Wave 5 planning

Wave 5 **W5-A Design & Contract Freeze** may start in a separate Leader session on current `origin/develop`. Product areas: Community, Practice, AI Authoring and AI Tutor, **planning/architecture only until explicit contract approval**. Required design dependencies include immutable QuizVersions, Assessment-vs-Practice separation, FB-015 deletion propagation and AI privacy/abuse controls. Recording, screen sharing and AI Proctoring remain deferred under their separate approvals.

Wave 5 engineering does not authorize production rollout. The Wave 4 release-gate track may continue in parallel under issue #129, with no cross-session concurrent pushes to the same implementation branches.

> **Post-closure scope amendment, 2026-10-30:** Product Owner subsequently placed
> **AI Proctoring W5-P1 design/privacy/contract freeze within Wave 5 planning**.
> The preceding historical deferral statement refers to the status at W4 closure,
> not the latest Wave 5 scope. W5-P2 implementation and all recording/screen
> sharing remain separately gated. See [W5-P scope/Leader handoff](w5-ai-proctoring-scope-handoff.md)
> and [issue #131](https://github.com/hhtuann/Quizopia_2.0/issues/131).
