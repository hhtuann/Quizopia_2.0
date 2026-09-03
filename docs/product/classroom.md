# Classroom

Status: **Baseline v0.1**

## Purpose

A classroom represents a teacher-managed learning group associated with a subject/grade context.

A classroom supports:

- membership;
- announcements;
- assignments;
- learning results/gradebook.

## Classroom creation

A teacher may create and manage owned classrooms.

Baseline classroom metadata includes:

- name;
- subject;
- grade;
- description (optional);
- owner teacher.

## Student joining methods

### Class code / invitation URL

A classroom can expose a join code and/or invitation URL.

Exact approval behavior for code/link joins is **TBD**:

- immediate membership; or
- teacher approval.

### Manual teacher addition

Teachers may manually add a student without using global user search.

Flow:

1. Teacher clicks "Add".
2. Teacher enters:
   - full name — required;
   - Gmail — required.
3. Teacher confirms.

If a verified Quizopia user already owns that Gmail address:

- create/activate classroom membership.

If no account exists:

- store a pending classroom invitation/placeholder record keyed by normalized Gmail;
- do **not** create a fake user account.

Later:

1. Student registers with that Gmail.
2. Student verifies the Gmail account.
3. The pending classroom invitation is claimed.
4. Classroom membership becomes available to the user automatically.

The teacher-entered name is invitation metadata. After account claim, the user's verified profile becomes canonical.

## Announcements

Teachers can publish classroom announcements.

Initial scope is a teacher-to-class bulletin/news stream, not a full social network.

## Assignments

A classroom assignment links classroom learning intent to a quiz publication.

Assignment metadata may include:

- title;
- instructions;
- publication reference;
- assigned time;
- due time where applicable.

Exact late-submission policy is **TBD**.

## Gradebook

Teachers can view classroom learning results across students and assignments.

Assessment Service remains authoritative for attempts/grades.

Classroom Service should consume assessment events or query documented APIs/read models rather than reading the Assessment database directly.
