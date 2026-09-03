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
