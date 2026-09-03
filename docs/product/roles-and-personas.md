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

Baseline behavior:

- authenticated user clicks "Register as teacher";
- teacher role is granted;
- no academic institution approval is required.

Audit details, anti-abuse limits, and whether an extra confirmation step is required remain implementation details unless later specified.

## Legacy roles

Quizopia 1.x roles such as `ACADEMIC_ADMIN` are not part of Quizopia 2.0 baseline.
