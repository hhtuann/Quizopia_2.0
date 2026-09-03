# Community

Status: **Baseline v0.1**

## Purpose

Community is the public content layer where teachers can contribute/share public quizzes and where learners/teachers can discover and discuss them.

This feature is broader than a traditional blog, but the first interaction model is post-based.

## Contribution flow

Teacher selects "Contribute content":

1. choose subject;
2. choose grade;
3. choose activity type:
   - `ASSESSMENT`
   - `PRACTICE`
4. choose one of the teacher's eligible public quizzes/publications;
5. enter post content;
6. publish.

## Community post

A post references the shared public quiz context and contains author/content metadata.

Posts support:

- reactions;
- comments.

## Ratings

Ratings conceptually belong to the public quiz, not to an individual community post.

One authenticated user should have at most one active rating per rateable public quiz, with update allowed.

## Copy / fork

An eligible public quiz may allow another teacher to copy/fork it into their own library.

Fork behavior:

- creates a new editable quiz owned by the copying teacher;
- preserves provenance to the source quiz version;
- never mutates the source quiz;
- respects the source author's `allowCopy`/equivalent permission.

Provenance should be visible to users where appropriate.

## Visibility

Quiz visibility concepts include:

- private;
- unlisted/link-access;
- public.

Exact naming is **TBD**, but the system must distinguish public discoverability from link-only sharing.

## Moderation

Because Quizopia is a public platform, baseline administration must be able to support:

- content reports;
- disabling/hiding abusive public content;
- disabling accounts where necessary;
- audit logging for sensitive moderation actions.

Full recommendation/trending algorithms are not required for the initial implementation.
