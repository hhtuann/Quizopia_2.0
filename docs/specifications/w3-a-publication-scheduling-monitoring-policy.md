# QUIZOPIA 2.0 — LEADER DECISION

## W3-A: Assessment Publication, Audience, Scheduling & Proctoring Scope

**Current-policy amendment (recorded 2026-10-13):**
[ADR-015](../decisions/ADR-015-w3b-w3c-leader-policy.md) adds approved W3-B/C
maxAttempts, unlimited individual duration, ABANDON, numeric/display policy and
C1..C10 delayed closure. The historical W3-A/LD-01..05 record below is preserved;
its former unbounded/Attempt/numeric deferrals and blanket force-close exclusion
are superseded only to that explicit scope. LD-02/03/05 and monitoring release
gates remain. Implemented W3-A is PR #78; W3-B/C engineering direction now has
Leader approval with conditions EC79-01..04 in ADR-015. Future artifacts remain
PROPOSED/UNIMPLEMENTED; production activation is not authorized.

**Status:** APPROVED — Product direction and scope
**Date:** 2026-10-09
**Baseline:** Wave 2 CLOSED through PR #71; Wave 3 foundation merged in PR #72
**Reference inspection:** `origin/develop` at `0290c6f`
**Implementation status:** Product policy recorded; engineering contracts remain required before feature coding.

**Follow-up record:** Leader-approved LD-01 through LD-05 recorded on 2026-10-11
in [section 16](#16-follow-up-leader-decisions-ld-01-through-ld-05).
The 2026-10-09 decision and its historical baseline above are preserved. Current
contract statements below are reconciled to this later approval; LD-01..05 were
not approved in PR #72 or PR #73. This document records no feature implementation.

### 1. Purpose

Wave 3 shall deliver a complete Assessment Core journey, with optional classroom proctoring.

A Teacher may use an immutable published QuizVersion to create an Assessment Publication targeting either one Classroom or the Public audience.

The Publication supports a scheduled participation window and a time-limited Attempt for each Student.

For eligible Classroom Publications, the Teacher may enable Activity Monitoring together with LiveKit camera monitoring.

AI-based suspicious-activity analysis remains an accepted future design but is deferred beyond Wave 3.

This Leader Decision supersedes earlier recommendations to exclude all Proctoring from Wave 3.

It does not alter the historical completion of Wave 2 or the already accepted Assessment/Grading policy from PR #72.

### 2. W3-A01 — Assessment Audience

**Status: ACCEPTED**

An Assessment Publication targets exactly one of the repository's existing audience types:

- `CLASS`
- `PUBLIC`

Do not rename `CLASS` to `CLASSROOM`.

For `CLASS`:

- The Publication references exactly one Classroom.
- The Teacher must be authorized to assign assessments to that Classroom.
- Participating Students must have authoritative Classroom membership.
- Classroom Service remains the source of truth for Classroom ownership and membership.
- A shared URL must not bypass membership checks.
- Eligible Classroom Publications may enable Proctoring.

For `PUBLIC`:

- No Classroom association is required.
- Classroom membership is not required.
- Classroom Proctoring is prohibited.
- Publication access and Attempt authorization must follow an explicit approved Public access contract.

**Follow-up LD-03:** PUBLIC participation requires an authenticated USER with
an authoritative STUDENT role, through a shared direct link with a suitably
non-enumerable locator. Identity account, token-validity and revocation rules
apply; a user with TEACHER + STUDENT may participate through STUDENT under the
same rules. Exact locator/route/error contracts and CLASS verification remain
engineering work under ASSESS-14/15.

A QuizVersion may be referenced by multiple independent Publications.

### 3. W3-A02 — Immutable QuizVersion Delivery

**Status: ACCEPTED — preserves ASSESS-01 and ASSESS-02**

Assessment Publication is a distinct Assessment-owned entity.

MVP lifecycle:

`DRAFT → OPEN → CLOSED`

The Teacher selects an existing immutable published QuizVersion.

When the Publication transitions to OPEN:

- Verify the Teacher is authorized to use that QuizVersion.
- Obtain the snapshot through an authorized service-to-service integration.
- Finalize immutable Assessment delivery content.
- Pin the grading policy version.
- Commit the Publication opening only when snapshot finalization succeeds.

All Attempts use the finalized delivery snapshot, not current QuizDraft data or later QuizVersions.

A Publication's QuizVersion reference is immutable after OPEN.

No cross-service database access.

Do not expose grading keys or teacher-only source snapshots in Student delivery APIs.

The exact snapshot handoff API, service credential, owner-binding mechanism and failure semantics require technical contract freeze.

### 4. W3-A03 — Participation Window

**Status: ACCEPTED**

Wave 3 Publications support a bounded participation window.

The window defines when an eligible Student may **start** an Attempt.

This is different from the time the Student is allowed to continue an already-started Attempt.

The backend must enforce the window using authoritative server time.

The contract must define:

- participation start instant;
- participation end instant;
- timezone-safe storage and display;
- validation of chronological ordering;
- boundaries for starting a new Attempt;
- behavior before opening and after the start window closes.

Opening a Publication and reaching its participation start time are distinct events.

Do not redefine the existing `DRAFT → OPEN → CLOSED` lifecycle merely to represent scheduling. Use separate scheduling/eligibility conditions if appropriate.

**Follow-up LD-01:** New starts require an OPEN Publication, an eligible Student
and `startAt <= serverNow < endAt`: start inclusive, end exclusive. Store and
compare timezone-safe authoritative instants; frontend-local times require
unambiguous conversion. Exact wire field names and chronological validation
remain engineering contracts; the notation here does not approve DTO fields.

**Wave 3 scope decision:** Scheduled participation applies to the Wave 3 CLASS/PUBLIC Assessment journey.

Future unbounded or practice Assessment behavior is not decided by this limited scope. Preserve relevant open questions.

### 5. W3-A04 — Per-Attempt Duration

**Status: ACCEPTED**

Each Wave 3 Publication supports an integer Attempt duration of 5 through 240 minutes inclusive, with one-minute granularity (follow-up LD-01).

Example: 40 minutes.

The authoritative Attempt start time and deadline are determined by Assessment Service.

The Student's local countdown is display-only.

The Student cannot extend the deadline through client-side clock changes, refreshing, reconnecting or tampering with requests.

Attempts started during the valid participation window have their own deadlines.

**Accepted deadline formula — follow-up LD-01:**

`attemptDeadlineAt = authoritativeStartedAt + configuredDuration`

Participation-window end does not shorten an already-started Attempt or close
Publication. Configuration changes must never retroactively shorten existing
authoritative Attempt deadlines. This does not prohibit every post-OPEN timing
mutation; accepted time-mutation rules still apply.

Example:

- Participation opens at 08:00.
- New Attempts stop at 08:30.
- Each Attempt lasts 40 minutes.
- Student starts at 08:25.
- Individual deadline is 09:05.

Expiration, cutoff, timeout finalization, resumption and response ordering remain
OPEN for W3-B. Follow-up LD-04 rejects CLOSE while any Attempt is authoritatively
active; overdue-Attempt handling and start/finalization races require engineering
contracts and W3-B terminalization before dependent implementation.

Backend deadline checks must not depend solely on scheduled background jobs.

### 6. W3-A05 — Classroom Monitoring Eligibility

**Status: ACCEPTED**

Proctoring is optional and applies only to eligible `CLASS` Publications.

Eligibility follows the accepted repository design:

- exactly one Classroom;
- authorized Teacher;
- authenticated eligible Classroom member;
- bounded participation availability;
- positive bounded Attempt duration;
- an active eligible Attempt for runtime Proctoring.

`PUBLIC`, unbounded and practice assessments are not eligible for this Classroom Proctoring scope.

**Follow-up LD-02:** CLASS Teachers explicitly choose whether Proctoring is
required. An unconfigured CLASS DRAFT has no implicit ON or OFF default and
cannot OPEN without an explicit recorded choice. The choice becomes immutable
upon OPEN. PUBLIC must have monitoring disabled and cannot enable Classroom
Proctoring. No additional Activity-only, AI-enabled or A/B modes are approved.

When Classroom Proctoring is enabled for Wave 3, the accepted feature bundle includes:

1. Browser Activity Evidence.
2. LiveKit Camera Monitoring.

The Teacher's decision to enable monitoring must be clearly disclosed to Students before starting.

Do not introduce a fictitious previously accepted `A/B` enum.

Do not assume `ACTIVITY_ONLY`, `ACTIVITY_AND_LIVE`, or other internal enum values are already established source-of-truth contracts.

The exact persisted monitoring configuration and UI terminology must be frozen against existing Proctoring documentation.

### 7. W3-A06 — Browser Activity Evidence

**Status: ACCEPTED FOR WAVE 3**

Implement the relevant accepted browser-side monitoring signal design for eligible monitored Attempts.

The intended evidence timeline may include supported events related to:

- Attempt lifecycle.
- Visibility and focus changes.
- Navigation and fullscreen state where observable.
- Camera availability and session state.
- Network disconnection and recovery.
- Duplicate Attempt tab indicators where technically observable.
- Answer-change metadata, without leaking answer content.

The evidence should be durable and tamper-resistant within the accepted architecture, with appropriate timestamps, authorization and retention.

Do not collect unrelated application activity, browsing history, other-tab URLs, keystrokes or clipboard contents.

Browser-observed signals are incomplete and may be manipulated. An event is not automatically proof of misconduct.

Do not automatically disqualify a Student, set the score to zero or impose a disciplinary sanction based on activity events.

Event schemas, Proctoring ownership boundaries and retention remain subject to detailed contract review.

### 8. W3-A07 — LiveKit Camera Monitoring

**Status: ACCEPTED FOR WAVE 3**

Enable authorized Teachers to view live camera streams of eligible, active Students for monitored CLASS Publications.

Preserve accepted ADR-004 and realtime architecture:

- LiveKit transports realtime media.
- Proctoring Service owns room/session and token orchestration.
- Assessment Service owns Attempt validity and deadlines.
- Classroom Service owns Teacher/Class membership authority.
- Media does not have to pass through the REST Gateway.
- Student answers must not use LiveKit DataChannels as authoritative persistence.

The implementation must ensure:

- Teacher can view only Students in authorized monitored Publications.
- Students cannot subscribe to other Students' camera streams.
- Camera sharing requires transparent browser-mediated permission.
- Students can see when live capture is active.
- Sessions end appropriately when their eligible Attempts end.
- Reconnect, device rejection, permission loss and network interruption are handled truthfully.
- Short-lived/scoped access and revocation are designed according to accepted infrastructure conventions.

LiveKit infrastructure being present in Docker Compose is not evidence that these product capabilities are implemented.

**Not part of Wave 3:**

- mandatory screen sharing;
- strict screen-capture enforcement;
- media recording;
- audio recording;
- remote device control;
- automatic facial identification.

These require separate approval if proposed later.

### 9. W3-A08 — AI Behavior Monitoring

**Status: ACCEPTED FUTURE DESIGN; DEFERRED AFTER WAVE 3**

The existing AI Proctoring design remains valid.

Potential future signals include:

- no face detected;
- multiple faces;
- phone/object detection;
- looking away;
- other source-approved suspicious-activity indicators.

Wave 3 must preserve extension points for future AI risk signals, but is **not required to implement**:

- AI media inference pipelines;
- computer-vision detectors;
- suspicion thresholds;
- automatic snapshot extraction for AI;
- AI risk scoring;
- AI alert processing/dashboard;
- AI-assisted disciplinary review workflow.

AI must never automatically declare cheating, fail a Student or sanction an account.

Do not delete existing AI monitoring documentation, and do not misclassify it as AI grading.

**Wave 3 Definition of Done must not require functioning AI Behavior Monitoring.**

### 10. W3-A09 — Publication and Attempt Lifecycle Safety

**Status: ACCEPTED invariants; detailed behaviors remain OPEN**

Assessment Service is authoritative for:

- Publication state;
- the immutable delivery snapshot;
- Attempt start/deadline;
- saved answers;
- submission/finalization;
- grading/results.

Monitoring failures must not corrupt submitted answers, alter grading rules or silently extend Attempt duration.

The accepted visibility policies remain:

- After successful submission and completed grading, Student may view the score.
- Correct answers and explanations become visible only after Publication is durably CLOSED.

A participation window ending is not itself sufficient to reveal correct answers.

The backend must never equate “new students cannot start” with “all active students have completed.”

**Follow-up LD-04:** Reject OPEN → CLOSED while any Attempt remains
authoritatively active, with a documented conflict response. Teacher may close
once all Attempts are terminal. CLOSE must handle overdue Attempts and
start/finalization races transactionally: concurrent start and CLOSE cannot
leave an unauthorized active Attempt in a CLOSED Publication. W3-B must ensure
deadline-expired Attempts eventually become terminal, with authoritative checks
independent of timely background processing. Automatic closure and force-close
are not introduced. Saved/unsaved answer treatment, deadline finalization,
submit semantics and response ordering remain contract gates.

### 11. W3-A10 — Privacy, Accessibility and Security

**Status: REQUIRED RELEASE GATES**

Monitoring involves potentially sensitive Student activity and camera data.

Before enabling live Proctoring for real learners, finalize:

- clear notice explaining collection and authorized viewers;
- applicable lawful basis and consent/authorization requirements;
- appropriate provisions for minors;
- accessible alternatives/accommodation;
- refusal and camera-unavailable behavior;
- Activity Evidence retention and deletion policy;
- access-control and access-audit rules;
- media infrastructure/deployment location;
- incident and data-subject request procedures;
- no-recording policy for this Wave.

Browser permission alone does not establish every required legal authorization.

No hidden camera activation.

No unrestricted Teacher access to all video rooms.

No indefinite monitoring-event retention.

An unavailable or denied camera must not be reported as active monitoring.

The exact outcome for a monitored Attempt when camera permission is denied or lost is OPEN and must be decided before enabling the feature in production.

Privacy and security review must examine the applicable Vietnamese legal requirements and any other jurisdiction relevant to deployment.

### 12. W3-A11 — Service Boundaries

**Status: ACCEPTED — preserve existing architecture**

**Quiz Service**

- Quiz and immutable QuizVersion ownership.
- Authorized version snapshot handoff.

**Assessment Service**

- Publication, delivery snapshot, Attempt, timing, answers, grading and results.

**Classroom Service**

- Class authority, membership and assignment eligibility.

**Proctoring Service**

- Proctoring session orchestration, monitoring events/evidence, LiveKit room/token lifecycle.

**AI Service**

- Future AI monitoring integrations, without creating a Wave 3 delivery dependency.

**Identity Service**

- Authoritative USER identities, roles and existing authentication lifecycle.

**Gateway**

- Existing HTTP access/routing and appropriate security integration.

No cross-service database reads or cross-service foreign keys.

No duplication of authoritative Assessment answer state inside LiveKit or Proctoring.

Redis/realtime outages must not cause loss of authoritative PostgreSQL-backed submissions/results.

Security checks required for privileged access must fail closed when they cannot be authoritatively verified.

### 13. Contract Items That Remain Open

The follow-up LD-01..05 approval resolves the product choices identified in
section 16. The following dependencies remain open before dependent work:

- Exact PUBLIC locator/route/error and safe projection contracts (ASSESS-14/15).
- Future unbounded/practice assessment support (including ASSESS-17).
- Allowed Attempt count and resumption model.
- Timing wire fields, chronological validation and local-time conversion contract.
- Implementation/validation of the accepted 5–240-minute integer duration.
- Handling when an Attempt deadline expires.
- Reject-CLOSE conflict, overdue-Attempt handling and transactional races (ASSESS-13).
- Autosave revisions and retry-safe submission.
- Exact QuizVersion service-to-service authorization contract.
- CLASS ownership/membership verification mechanism.
- Monitoring persisted representation and UI terminology (PROCTOR-10), within LD-02.
- Activity Evidence schema, event transport and retention.
- Camera permission refusal, loss and accommodations.
- Proctoring session/token lifetime and revocation.
- Production LiveKit deployment and privacy controls.
- PROCTOR-01..09 items not resolved by this Leader Decision.
- Numeric input syntax and score precision from the Assessment grading contracts.
- LD-05 security implementation, trustworthy recovery mechanism and verification (ID-05).

These issues shall remain OPEN unless already resolved by authoritative accepted repository documents.

Engineering choices may be settled within the normal implementation process if they do not introduce new product/security policy. Product-level conflicts must return to the Leader.

### 14. Wave 3 Delivery Sequence

Use separate bounded PRs.

**W3-A — Publication Foundation**

CLASS/PUBLIC audience, Classroom authorization, QuizVersion handoff, participation schedule, configured duration, immutable opening snapshot and Teacher Publication UX.

**W3-B — Attempt Lifecycle**

Student access, start/resume, answer persistence, deadline, submit and expiry behavior.

**W3-C — Grading and Results**

Pinned grading policy, deterministic grading, Student score visibility, Teacher results and post-CLOSED answer review.

**W3-M1 — Activity Monitoring**

Browser monitoring events, Proctoring session/evidence API, Teacher activity dashboard.

**W3-M2 — LiveKit Camera Monitoring**

Authorized room/token lifecycle, Student camera capture, Teacher video grid, connect/disconnect handling and privacy controls.

Activity Monitoring and LiveKit Camera Monitoring are required Wave 3 outcomes even though they are separate workstreams.

AI Behavior Monitoring remains after Wave 3.

### 15. Wave 3 Closure Criteria

Wave 3 cannot close until:

- CLASS and PUBLIC Publication flows operate against real services.
- Teacher can configure bounded participation and Attempt duration.
- Immutable QuizVersion delivery is verified.
- Classroom ownership and Student membership authorization pass.
- Student can start, resume, save, submit and receive grading.
- Time windows and deadlines are enforced by the server.
- Results obey score/review visibility policies.
- CLASS monitoring can be enabled or disabled under the accepted contract.
- Activity Evidence and authorized Teacher monitoring operate.
- LiveKit camera monitoring works with real browser permissions.
- Monitoring security/privacy and failure handling are verified.
- Real browser-to-backend integration tests pass.
- Relevant CI and security gates pass.
- No unresolved Blocker/High issue remains.

**AI suspicious-activity detection, media recording and strict screen sharing are not Wave 3 closure requirements.**

---

**Leader-approved product direction is final. Specific OPEN contracts still require source-truth reconciliation and approval before dependent implementation.**

### 16. Follow-up Leader Decisions LD-01 through LD-05

**Status: APPROVED — all five decisions explicitly approved by the Leader**

**Recorded:** 2026-10-11. **Verified documentation baseline:**
`origin/develop @ 87ff6b064b972400cd4903f7bc4fec9375e559a1`.
This is a follow-up to the historical 2026-10-09 W3-A decision, recorded after
PR #72 and PR #73. It preserves ASSESS-01..04, GRADE-01..06, W3-A scope and
accepted architecture. Approval records policy, not executable contracts or
working integrations. No additional coordinator recommendation is approved here.

#### LD-01 — Participation Timing and Duration

- New Attempt starts require OPEN Publication, eligible Student and
  `startAt <= serverNow < endAt`; start inclusive, end exclusive.
- `attemptDeadlineAt = authoritativeStartedAt + configuredDuration`.
  Participation-window end does not shorten an already-started Attempt.
- Duration is an integer number of minutes from 5 through 240 inclusive,
  with one-minute granularity.
- Store and compare timezone-safe authoritative instants. Frontend-local times
  require unambiguous conversion.
- Window end does not close Publication. OPEN, scheduled eligibility, individual
  deadlines and CLOSED remain distinct.
- Configuration changes must never retroactively shorten existing authoritative
  Attempt deadlines. This decision does not ban all post-OPEN timing mutations;
  existing accepted time-mutation rules remain applicable.
- W3-B expiration, cutoff, timeout finalization, resumption and response ordering
  remain unresolved. Unbounded assessments and Practice timing remain deferred.

#### LD-02 — Monitoring Configuration

- CLASS Teachers explicitly choose whether Classroom Proctoring is required.
  An unconfigured CLASS DRAFT has no implicit ON or OFF default and cannot OPEN
  without an explicit recorded monitoring choice.
- Enabled monitoring means the accepted Browser Activity Evidence plus LiveKit
  Camera bundle. No additional Activity-only, AI-enabled or A/B modes are approved.
- PUBLIC must have monitoring disabled and cannot enable Classroom Proctoring.
- Monitoring choice becomes immutable upon OPEN.
- Runtime capture requires eligible active Attempts and future W3-M1/M2
  authorization, privacy and failure contracts.
- Engineering may propose persisted representation and UI terminology without
  inventing product modes. PROCTOR-10 remains partially resolved on that basis.

#### LD-03 — PUBLIC Authentication and Discovery

- PUBLIC participation requires authenticated USER with authoritative STUDENT
  role. Identity account, token-validity and revocation rules apply.
- TEACHER + STUDENT users may participate through STUDENT under the same rules.
  No permission is granted or denied here for an owner attempting their own
  Publication.
- No Classroom membership is required. Wave 3 uses a shared direct link with a
  suitably non-enumerable locator.
- No searchable public directory, anonymous guest participation, password
  protection or anonymous-session identity is required for Wave 3.
- Teacher management stays owner-authorized; Student APIs expose safe projections.
- Link possession never bypasses authentication, lifecycle, timing or Attempt
  eligibility. Exact locator/route/error contracts remain engineering work.

#### LD-04 — Closing With Active Attempts

- Reject OPEN → CLOSED while any Attempt remains authoritatively active, with a
  documented conflict response.
- Participation-window end prevents new starts; it does not automatically close
  Publication or finalize Attempts.
- W3-B must ensure deadline-expired Attempts eventually become terminal, with
  authoritative checks independent of timely background processing.
- CLOSE must handle overdue Attempts and start/finalization races transactionally.
  Concurrent start and CLOSE cannot leave an unauthorized active Attempt in a
  CLOSED Publication.
- Teacher may close once all Attempts are terminal. Only durable CLOSED enables
  eligible submitted-owner answer/explanation review.
- Automatic closure and force-close are not introduced. Saved/unsaved answer
  treatment, deadline finalization and submit semantics still require W3-B contracts.

#### LD-05 — Redis Revocation Outage and Recovery

- Required revocation/account authority must be trustworthy before protected
  operations are authorized. Signature-valid, unexpired JWTs alone cannot bypass
  required near-immediate revocation checks.
- Affected protected Assessment, Classroom and Proctoring operations fail closed
  during required authority unavailability. Prefer sanitized service-unavailable
  errors over misleading invalid-credential errors when appropriate, subject to
  API contract review; no exact wire error/status is frozen here.
- Do not invalidate refresh credentials, silently log out users or create refresh
  loops solely because revocation authority is unavailable.
- No automatic Identity fallback or degraded authenticated lower-risk read policy
  is approved for initial Wave 3.
- Redis connectivity/health alone does not establish trustworthy recovery.
  Recovery requires trustworthy coverage or reconstruction from authoritative
  durable state. Until authority is trustworthy, affected protected operations
  remain unavailable.
- Truly public, nonprivileged infrastructure operations may remain available
  where independent of the outage. This is not a PUBLIC Assessment bypass.
- Dev1 must prepare bounded security implementation and verification contracts;
  see [Assessment Core security contract gate](assessment-core-policy.md#ld-05--bounded-security-implementation-and-verification-contract).
  The later approved Candidate B architecture and bounded Identity prerequisite
  are recorded in the [LD-05 addendum](../architecture/identity-authority-admission.md).
  End-to-end implementation, recovery verification and activation remain outstanding. No authentication subsystem rewrite is authorized by this decision.

ASSESS-13/14/17, PROCTOR-10 and ID-05 are **PARTIALLY RESOLVED**: the policy
portions above are accepted; identified engineering and later-wave contracts
remain open. ASSESS-15 remains **OPEN — ENGINEERING CONTRACT**. W3-B/C/M1/M2
contract and release gates remain in force. This documentation task implements
neither CLOSE/finalization nor revocation/monitoring runtime.

**END OF LEADER DECISION**

### 17. W3-B/W3-C accepted amendments and proposed engineering pack

[ADR-015](../decisions/ADR-015-w3b-w3c-leader-policy.md) is the later accepted
product authority. LD-01 now permits duration 0 with null individual deadline,
while retaining bounded participation windows and positive pinned deadlines.
Unlimited CLASS cannot enable monitoring. No post-OPEN duration mode switch.
LD-04 ordinary active-Attempt rejection remains; only the approved two-step,
committed accessible notice plus individual 48-hour cutoff mechanism may finalize
ACTIVE unlimited work. Workers never close Publication. Student ABANDON is
explicit/ungraded; maxAttempts 0/unlimited or 1..10 freezes OPEN; numeric Student
tokens are exactly four ASCII characters, score display to 2 places HALF_UP only.
Historical LD-01/LD-04 wording above is unchanged and is not the current deferral.

The [integrated contract pack](../architecture/w3b-w3c-contract-review-evidence.md)
defines the engineering direction conditionally approved in ADR-015. Independent
read-only amendment review, real inbox deployment, implementation/Leader merge
and security/runtime/release gates remain. Practice/general inbox scope and
monitoring privacy/operational release gates are unchanged.
