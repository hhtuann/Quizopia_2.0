# AI Features

Status: **Baseline v0.2**

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
2. teacher chats with AI;
3. teacher requests a structure such as "50 questions: 25 SINGLE_CHOICE, 25 TRUE_FALSE_MATRIX";
4. AI retrieves relevant source content;
5. generated quiz content is inserted into editable authoring source;
6. teacher reviews/edits;
7. normal Quiz Markdown validation runs;
8. teacher proceeds to publication configuration.

## Internal generation representation

**Proposed, not yet accepted as a product contract:**

Prefer generating a structured question representation first, then rendering canonical Quiz Markdown in application code.

This is intended to reduce malformed numbering/options/type syntax while preserving a Markdown-first teacher experience.

Finalize this together with the Quiz Markdown grammar.

## Source grounding

AI authoring should preserve enough provenance to help the teacher verify generated content against source material where practical.

Exact citation/source-reference UX remains TBD.

## AI tutor

Practice mode may expose a context-aware AI tutor.

It must obey answer-review/hidden-answer policy.

Exact tutor modes/policies remain TBD.

## Infrastructure baseline

Accepted initial data/storage direction:

- AI Service owns AI workflows;
- source documents are stored through the S3-compatible object-storage abstraction;
- local object storage uses MinIO;
- vector retrieval starts with PostgreSQL `ai_db` + pgvector;
- provider/model choice remains TBD;
- domain logic must not hard-code one AI provider;
- local/test development needs a fake/test provider path so core work is not blocked by external model credentials.
