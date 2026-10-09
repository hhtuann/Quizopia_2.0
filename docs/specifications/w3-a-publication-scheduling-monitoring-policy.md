# QUIZOPIA 2.0 — LEADER DECISION

## W3-A: Assessment Publication, Audience, Scheduling & Proctoring Scope

**Status:** APPROVED — Product direction and scope
**Date:** 2026-10-09
**Baseline:** Wave 2 CLOSED through PR #71; Wave 3 foundation merged in PR #72
**Reference inspection:** `origin/develop` at `0290c6f`
**Implementation status:** Documentation update required before feature coding.

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

**Unresolved:** Whether PUBLIC participation strictly requires an authenticated STUDENT account, how public discovery/sharing works, and the precise authorization rules tracked by existing Assessment open questions.

Do not automatically close these questions simply because the `PUBLIC` audience has been approved.

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

The exact field names and inclusive/exclusive boundary convention belong to the API freeze.

**Wave 3 scope decision:** Scheduled participation applies to the Wave 3 CLASS/PUBLIC Assessment journey.

Future unbounded or practice Assessment behavior is not decided by this limited scope. Preserve relevant open questions.

### 5. W3-A04 — Per-Attempt Duration

**Status: ACCEPTED**

Each Wave 3 Publication supports a positive bounded Attempt duration.

Example: 40 minutes.

The authoritative Attempt start time and deadline are determined by Assessment Service.

The Student's local countdown is display-only.

The Student cannot extend the deadline through client-side clock changes, refreshing, reconnecting or tampering with requests.

Attempts started during the valid participation window have their own deadlines.

**Recommended timing interpretation, pending contract freeze:**

`attemptDeadlineAt = authoritativeStartedAt + configuredDuration`

Closing the new-start window should not automatically shorten a previously valid Attempt.

Example:

- Participation opens at 08:00.
- New Attempts stop at 08:30.
- Each Attempt lasts 40 minutes.
- Student starts at 08:25.
- Individual deadline is 09:05.

The exact cutoff, resumption, close/finalization semantics and interaction with manually closing a Publication remain OPEN until the appropriate dependent contract is approved.

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

Teacher may choose whether the CLASS Publication requires Proctoring.

When Classroom Proctoring is enabled for Wave 3, the intended feature bundle includes:

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

Detailed decisions for expiration, automatic submission, Teacher early closure, simultaneous finalization and response ordering remain part of the Attempt/closure contract freeze.

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

Do not present the following as approved merely because this document proposes an implementation direction:

- PUBLIC authentication/eligibility and discoverability (including ASSESS-14).
- Future unbounded/practice assessment support (including ASSESS-17).
- Allowed Attempt count and resumption model.
- Precise start/end window boundary semantics.
- Maximum/minimum configured Attempt duration.
- Handling when an Attempt deadline expires.
- Whether/how Teachers may close early while Attempts are active.
- Autosave revisions and retry-safe submission.
- Exact QuizVersion service-to-service authorization contract.
- CLASS ownership/membership verification mechanism.
- Proctoring mode encoding and default.
- Activity Evidence schema, event transport and retention.
- Camera permission refusal, loss and accommodations.
- Proctoring session/token lifetime and revocation.
- Production LiveKit deployment and privacy controls.
- PROCTOR-01..09 items not resolved by this Leader Decision.
- Numeric input syntax and score precision from the Assessment grading contracts.

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

**END OF LEADER DECISION**
