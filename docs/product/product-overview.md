# Product Overview

Status: **Baseline v0.1**

## Vision

Quizopia 2.0 is a public web platform where anyone can create an account, learn from public quizzes, participate in classroom assignments, and—after enabling teacher capabilities—create, publish, share, and manage quizzes.

The product combines four major experiences:

1. **Public quiz platform** — quizzes can be shared by link and may allow guest participation.
2. **Classroom learning** — teachers create classes, add/invite students, publish announcements, assign quizzes, and view learning results.
3. **Authoring and learning tools** — teachers author quizzes with a Markdown-first editor and can use AI-assisted generation; learners can use practice mode and a future AI tutor.
4. **Assessment and proctoring** — class-only, time-bounded assessments may optionally enable camera-based proctoring and suspicious-event logging.

## User model

The platform has three security roles:

- `STUDENT`
- `TEACHER`
- `ADMIN`

Every registered user starts with `STUDENT`.

A user may additionally obtain `TEACHER` without academic-institution approval or another external eligibility constraint.

A user may simultaneously have `STUDENT` and `TEACHER`.

The UI uses an **active workspace/persona** concept:

- Learning workspace
- Teaching workspace

When a teacher opens a public quiz to take it, the UI switches to the learner experience. The backend does not remove the user's teacher role or issue a temporary "student-only" identity.

## Core user journeys

### Learner

- Register/login.
- Open a public quiz link.
- Join/use classrooms.
- Complete assigned assessments/practice activities.
- View results when publication policy allows.
- Use practice mode.
- Interact with community content.
- Rate public quizzes.
- Comment/react on community posts.

### Teacher

- Enable teacher capability.
- Create/manage classes.
- Invite/add students.
- Publish class announcements.
- Create quizzes in folders.
- Author quiz content with Markdown/live preview.
- Import quiz content from Excel/DOCX templates.
- Use AI-assisted quiz generation from uploaded documents.
- Configure and publish assessments/practice activities.
- Share public quiz links.
- Assign activities to classes.
- Generate offline DOCX exam papers.
- Monitor eligible proctored assessments.
- View results/reports/gradebook.
- Contribute public quiz content to the community.
- Allow other users to copy/fork eligible public quizzes.

### Admin

- Manage user accounts and platform-level account status.
- Support moderation actions for public/community content.
- Audit sensitive administrative actions.

## Modes

### Assessment

For tests, exams, homework, competitions, or other scored submissions.

May configure:

- availability window;
- attempt duration;
- maximum attempts;
- password;
- audience;
- shuffle questions;
- shuffle options;
- result visibility;
- answer review visibility;
- proctoring when eligible.

### Practice

For self-paced learning.

Practice does not use assessment availability/duration settings.

It may support:

- immediate question feedback;
- explanations;
- AI tutor;
- public/class distribution.

## Public access

For Wave 3, [approved W3-A follow-up LD-03](../specifications/w3-a-publication-scheduling-monitoring-policy.md#ld-03--public-authentication-and-discovery)
requires authenticated USER with authoritative STUDENT for PUBLIC participation,
including TEACHER + STUDENT through STUDENT under the same rules. Identity
account/token-validity/revocation rules apply; Classroom membership is not required.
Sharing uses a direct link with a suitably non-enumerable locator; possession
bypasses no lifecycle, timing or eligibility rule. No searchable directory,
anonymous guest participation, passwords or anonymous-session identity is required
for Wave 3. Exact locator/route/error contracts remain open. This adds no rule
about an owner attempting their own Publication. The guest behavior below is
broader future scope.

A public publication may allow a guest who has never registered to take the quiz through the public link.

Authenticated users have stronger identity-based controls. Guest attempt limits are best-effort because a normal web platform cannot reliably identify the same anonymous human across browsers/devices.

Class publications require an authenticated account and classroom membership.

## Key product principles

- Authoring content and delivery configuration are separate concepts.
- Published quiz content is immutable.
- Learner attempts are server-authoritative.
- Public content and classroom delivery coexist.
- Proctoring is evidence/risk support, not an automatic guilt engine.
- AI assists humans; it does not silently publish unreviewed content.

## W3-B/C policy and proposed engineering extension

The later [ADR-015](../decisions/ADR-015-w3b-w3c-leader-policy.md) accepts unlimited
individual duration 0 (bounded start windows remain), configurable maxAttempts,
explicit retake/ABANDON, strict numeric input and two-step delayed Teacher closure.
[The integrated engineering pack](../architecture/w3b-w3c-contract-review-evidence.md)
proposes Assessment-owned closure notices in a real authenticated inbox, atomic
local grading/results and existing service authority boundaries. This scoped
notice projection transfers no Classroom/Identity authority and adds no service.
W3-A PR #78 is implemented; all W3-B/C routes/schema remain proposals for review.
Monitoring stays bounded CLASS-only; unlimited CLASS and PUBLIC cannot ENABLE.
Practice/general inbox/gradebook events and operational/privacy release gates remain.
