# Wave 5 scope amendment & Leader handoff — W5-P AI Proctoring

> **Product Owner decision date:** 2026-10-30.
>
> **Status:** **APPROVED FOR WAVE 5 PLANNING / W5-P1 DESIGN & PRIVACY-CONTRACT FREEZE ONLY.** W5-P2 detector/inference implementation, new camera capture, live-learner activation and any production deployment are **NOT APPROVED**. Do not interpret inclusion in the roadmap as authorization to implement or enable surveillance.
>
> **Tracking:** [W5-P planning and decision issue #131](https://github.com/hhtuann/Quizopia_2.0/issues/131).
>
> **Source precedence:** this scope-scheduling amendment supplements, and does **not** override, [existing Proctoring policy](../product/proctoring.md), [AI principles](../product/ai-features.md), [W3-M security/monitoring contract](../specifications/w3-m-proctoring-contract.md), [ADR-018 erasure](../decisions/ADR-018-fb015-permanent-quiz-erasure.md), or active production release gates.

## 1. Product scope decision and timeline

Wave 5 planning now explicitly has **five** product areas, not four:

| Workstream | Product area | Current authorization |
| --- | --- | --- |
| W5-B | Community | W5-A product/contract design before coding |
| W5-C | Practice | W5-A product/contract design before coding |
| W5-D | AI Authoring | W5-A product/contract design before coding |
| W5-E | AI Tutor | W5-A product/contract design before coding |
| **W5-P** | **AI Proctoring (AI-assisted exam monitoring)** | **W5-P1 planning/design approved; W5-P2 implementation conditional on separate privacy/security and Leader approvals** |

**W5-A** is still the common **Design & Contract Freeze** entry gate, and its planning package must now include **W5-P1**. **W5-F Integration & Acceptance** remains the cross-workstream integration track. It incorporates W5-P2 *only if* W5-P2 is independently approved and ready; W5-P2 is not a blocker to independent Community, Practice, AI Authoring or AI Tutor delivery. Numbering `W5-P` avoids renaming the already proposed W5-B/C/D/E/F workstreams.

Wave 4 engineering delivery was closed in [W4 final engineering closure](w4-final-engineering-closure.md) and [PR #130](https://github.com/hhtuann/Quizopia_2.0/pull/130). Existing W3/W4 Proctoring/LiveKit/Realtime provides the **integration foundation**, not approved AI inference or monitoring activation. The Wave 4 release readiness issue [#129](https://github.com/hhtuann/Quizopia_2.0/issues/129) and inherited W3 production gates remain OPEN. **Production NO-GO.**

## 2. W5-P1 — Mandatory design, privacy and contract package

The new Wave 5 session must inventory the **actual** Proctoring, Assessment, Classroom, Identity, Gateway, Realtime/LiveKit and AI Service implementations and contracts before designing a new pipeline. Reuse accepted authorization and admission boundaries; do not infer a working AI detector from an extension point.

Prepare a reviewable package addressing:

1. **Product scope and admission.** Preserve currently eligible, explicitly monitored, time-bounded **CLASS** Publications and active Attempts; **PUBLIC** and **Practice** monitoring stay prohibited. Teacher monitoring and STUDENT audience memberships remain authoritative. No implicit `AI_ENABLED` monitoring mode and no surprise activation from the existing `UNCONFIGURED/ENABLED/DISABLED` Assessment choice. Any separate AI enablement semantics require explicit product approval.
2. **Candidate detections, not assumed requirements.** Compare human-review signals such as absent/multiple faces, possible phone/object presence and looking away, including reliability limits and demographic/accessibility risks. Define what can be done on existing authorized activity signals alone vs ephemeral video analysis (which itself needs independent approval). Detector/model/provider/version, thresholds, sampling, false-positive rates, calibration, evaluation datasets and budget **remain TBD**.
3. **Evidence/data minimization.** Specify whether frames or snapshots are needed at all, if anything may be persisted, access audit, allowed processing location, real retention/deletion periods, provider obligations, and FB-015 Quiz erasure propagation. **Do not default to storing frames, video, biometrics or training data**; browser camera permission is not equivalent to lawful basis or permission for AI inference.
4. **Review workflow.** AI produces bounded, explainable **suspicious/risk signals for an authorized human reviewer** with confidence/uncertainty and source/clock provenance, corrections/disputes and audit; it never determines cheating as fact, automatically fails/sanctions a Student, changes grade, result, Attempt lifecycle or classroom membership.
5. **Security and failure handling.** Freeze service ownership, exact API/event schemas, USER/SERVICE scopes, downstream LD-05 and resource checks, isolation, consent/notice, minors, accommodation, camera-off/denied/lost outcomes, tamper/untrusted media, retry/deduplication/revocation and error behavior. No cross-service DB joins, untrusted external data leakage, retention by log/telemetry, or automated fallback permitting forbidden access. Define opt-in flag(s) default **OFF**, kill switch and provider cost/rate budgets.
6. **Independent review and acceptance.** Threat model; qualified Privacy/Security and product signoffs; offline synthetic/fake-provider tests; detector-quality/bias calibration and adversarial false-alert tests; real Browser→Gateway→services acceptance *only on disposable synthetic identities/media* after test authorization. Non-disposable learner inference or staging/production requires further explicit authorization and evidence, not a green unit CI.

Link/resolve the pre-existing [PROCTOR-01..12 questions](../open-questions.md#proctoring), especially **PROCTOR-01/02/03/06/07/11/12**, before approving data collection or W5-P2. Do not choose a 7-day/30-day retention default by inference.

### W5-P1 exit gate (human approval required)

The Leader must approve the exact MVP detector candidates, data-flow/ownership, API/event contracts, authorization, review UX, flags, synthetic-only testing plan, service-failure semantics, costs and acceptance metrics. A qualified independent Privacy/Security reviewer must evaluate lawful basis, notices/consent, minors, disability/accommodation, data handling/retention and erasure before any real-learner inference. If either gate is missing, **W5-P2 BLOCKED**; documentation and synthetic feasibility analysis can still proceed within approved bounds.

## 3. W5-P2 — Implementation, only if separately authorized

After W5-P1 freeze and recorded authorizations, propose **separately reviewable** workstreams/PRs for any approved inference adapter, tightly scoped signals, authorized teacher-review presentation, privacy-preserving event storage and disposable end-to-end tests. Preserve immutable Assessment grading and authoritative Attempt state. Proof of a detector in synthetic tests **does not** authorize a production roll-out. Product/safety/operational release gates are independent and remain **NO-GO** until explicitly satisfied.

W5-P2 is not automatically queued, and nothing in this amendment authorizes changing release flags, existing Proctoring mode semantics, provider configuration or migration on non-disposable student databases.

## 4. Explicitly excluded / still deferred

- Full-session video/audio recording, microphone capture, automatic stored suspicious-event snapshots, mandatory/strict screen sharing, screen recording, remote device control, external tab/URL surveillance, identity recognition via facial biometrics and emotion identification are **not authorized by W5-P inclusion**.
- AI auto-conviction, auto-fail, automatic penalties, grade/Attempt mutation, disabling accommodations, and undisclosed monitoring are **prohibited**.
- Detection examples are exploration inputs, not approved algorithmic features, datasets, thresholds or accuracy claims.
- No real-learner capture/processing, staging deletion on non-disposable data or Production GO is granted.

Any future recording/screen-sharing expansion remains a **separate product/privacy gate**, not a hidden dependency or automatic part of W5-P.

## 5. Wave 5 session handoff — action list

1. Fetch fresh `origin/develop` and inspect `AGENTS.md`, `DESIGN.md`, [project-status](project-status.md), [MVP plan](mvp-plan.md), [open questions](../open-questions.md), [Proctoring](../product/proctoring.md), [AI features](../product/ai-features.md), [W3-M contract](../specifications/w3-m-proctoring-contract.md), [W4 closure](w4-final-engineering-closure.md) and relevant code/OpenAPI.
2. Record all five Wave 5 areas, including **W5-P1 in W5-A**; retain current proposed W5-B/C/D/E/F ordering while separately deciding W5-P2 dependency/priority after privacy review.
3. Create a **W5-P1 design-and-decision matrix** with exact unresolved questions, contract owners, risks, validation/acceptance criteria and permission gates for Product Owner review. Do **not** write inference production code as a prerequisite.
4. Keep the Wave 4/FB-015 issue and production release work separate; do not reopen W4 engineering. Release issues [#129](https://github.com/hhtuann/Quizopia_2.0/issues/129) and [#97](https://github.com/hhtuann/Quizopia_2.0/issues/97) remain open.
5. Follow the existing workflow: inspect → draft decisions/contracts → Leader approves → gated implementation PRs → independent security/CI review → Leader merge. No auto-merge/deploy.

**Decision summary:** AI Proctoring is **IN** Wave 5 scope at the **planning/design** level, **not yet implementation-approved**. This explicitly supersedes the earlier statement “AI Proctoring is outside Wave 5,” while preserving all pre-existing safety gates.
