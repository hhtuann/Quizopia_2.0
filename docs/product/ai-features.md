# AI Features

Status: **Baseline v0.1**

## Principles

- AI assists authoring and learning.
- AI-generated quiz content remains editable/reviewable before publication.
- AI tutor must respect answer-review policies.
- AI proctoring produces suspicious-event signals, not automatic guilt decisions.
- Uploaded source documents require access control and retention rules.

## AI-assisted quiz authoring

The teacher uses the normal manual Markdown authoring screen:

- one pane: live preview;
- one pane: Markdown source;
- AI assistant integrated into the authoring workflow.

Example flow:

1. teacher uploads source documents such as books, slides, PDF/DOCX/PPTX;
2. teacher chats with the AI;
3. teacher asks for a structure such as:
   - "Generate 50 questions";
   - "25 SINGLE_CHOICE";
   - "25 TRUE_FALSE_MATRIX";
4. AI reads/retrieves relevant source content;
5. generated quiz content is inserted into the editable authoring source;
6. teacher reviews/edits;
7. normal Quiz Markdown validation runs;
8. teacher proceeds to publication configuration.

## Internal generation format

**Proposed, not yet accepted as a hard requirement:**

Prefer generating a structured question representation first, then render canonical Quiz Markdown in application code.

Reason:

- reduces malformed question numbering/options;
- reduces invalid type-specific syntax;
- keeps the teacher-facing experience Markdown-first.

This decision should be finalized with the Quiz Markdown grammar.

## Source grounding

AI authoring should retain enough provenance to help the teacher verify generated questions against source material where practical.

Exact citation/source-reference UX is **TBD**.

## AI tutor

Practice mode may expose an AI tutor backed by the relevant quiz/source context.

It should support learning-oriented interactions while respecting hidden-answer policy.

## AI infrastructure

Exact implementation is not yet fixed.

Candidate building blocks include:

- Spring-based AI integration;
- document extraction/parsing libraries;
- PostgreSQL + pgvector for early vector search;
- object storage for source files;
- model/provider abstraction.

Do not hard-code a single AI provider into domain logic without an accepted decision.
