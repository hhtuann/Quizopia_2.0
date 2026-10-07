# Roles and Personas

Status: **Baseline v0.1**

## Security roles

### STUDENT

Default role for every verified account.

Capabilities include:

- take eligible quizzes;
- use public quiz links;
- join/access classrooms;
- complete assigned activities;
- view own results according to publication policy;
- participate in community interactions.

### TEACHER

Additional role that a user may enable through "Register as teacher".

Current product requirement: no academic-admin approval or institution verification is required.

Capabilities include:

- manage owned classrooms;
- create quiz content;
- publish assessments/practice activities;
- assign publications to classes;
- view results/reports for owned classroom/publication contexts;
- use teacher AI authoring tools;
- use proctoring where allowed;
- create community contribution posts.

### ADMIN

Platform-wide administrative role.

Capabilities include:

- account management;
- account status controls;
- moderation support;
- sensitive administrative audit actions.

## Multiple roles

A user may simultaneously hold:

- `STUDENT`
- `TEACHER`

The role set is an authorization fact.

## Active workspace/persona

Frontend navigation uses an active workspace:

- `LEARNING`
- `TEACHING`

Example:

1. User has `STUDENT + TEACHER`.
2. User is browsing the Teaching workspace.
3. User opens a public quiz to take it.
4. Frontend navigates to learner quiz-taking UI.
5. Authorization still sees the user's complete role set.

Do not implement workspace switching by deleting roles, changing database roles, or issuing an artificial student-only account.

## Teacher enrollment

MVP behavior:

- an authenticated Quizopia `USER` whose authoritative account is `ACTIVE`,
  email-verified, and retains the required persisted `STUDENT` role may
  self-enable `TEACHER`; missing `STUDENT` is an inconsistent, ineligible
  lifecycle state;
- no academic-admin approval, institution verification, invite code, or separate
  approval workflow is required;
- `TEACHER` is additive: `STUDENT` and any other valid existing roles remain;
- enablement is idempotent and returns the same successful outcome when the user
  is already a teacher;
- the first real grant is durably audited by Identity in the same transaction as
  the role mutation;
- there is no dedicated teacher-enablement rate limiter for MVP;
- the access token used to enable the role remains unchanged. The browser must
  use the existing refresh flow to obtain a new JWT with the current `TEACHER`
  claim.

Identity derives the target user only from the authenticated USER subject.
Client-supplied user IDs or role values are not accepted, and SERVICE principals
cannot use the self-enablement operation.

## Legacy roles

Quizopia 1.x roles such as `ACADEMIC_ADMIN` are not part of Quizopia 2.0 baseline.
