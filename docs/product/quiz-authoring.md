# Quiz Authoring

Status: **Baseline v0.1**

## Quiz library

Teachers have a personal quiz library.

The library supports folders for organizing content.

Quiz metadata includes at least:

- title;
- subject;
- grade;
- description;
- folder;
- owner.

Exact subject/grade catalogs are still to be finalized.

## Authoring methods

A teacher may create quiz content by:

1. authoring directly in the Markdown editor;
2. importing from an Excel template;
3. importing from a DOCX template;
4. using AI assistance while authoring.

The exact Excel/DOCX templates will be supplied later and are not defined in Context Pack v0.1.

## Markdown-first editor

The primary manual-authoring experience is split into two panes:

- live quiz preview;
- editable Quiz Markdown source.

The preview recognizes questions/options and renders question-type-specific UI.

Example user-facing notation:

```text
Câu 1. Nội dung câu hỏi

A. phương án A
*B. phương án B
C. phương án C
D. phương án D
```

Meaning:

- `Câu <number>.` begins a question;
- `A.` ... `D.` identify ordered options/statements where applicable;
- `*` marks a correct option/statement.

The final type-disambiguation grammar is still **TBD** and is tracked in `docs/specifications/quiz-markdown-spec.md`.

## Supported question types

Quizopia 2.0 keeps the four legacy objective question types:

- `SINGLE_CHOICE`
- `MULTIPLE_CHOICE`
- `TRUE_FALSE_MATRIX`
- `NUMERIC_FILL`

### NUMERIC_FILL

Keep the fixed four-character format intentionally required for the Vietnamese Ministry-of-Education-aligned question style used by this project. This is a deliberate Quizopia 2.0 override of the legacy analysis recommendation to remove the restriction. See `ADR-013-numeric-fill-format.md`.

The correct answer is exactly four characters.

The final allowed character set and normalization rules must stay consistent across:

- Markdown parser;
- Excel/DOCX import;
- backend validation;
- database constraints;
- grading;
- frontend validation.

Legacy behavior used digits, minus sign, and decimal point for the four-character answer.

## Validation before configuration/publication

When the teacher saves authoring content and proceeds to configuration, the system must validate at minimum:

- question numbering is continuous and in the expected order;
- option labels are in the expected `A -> B -> C -> D` order for question types that require them;
- required answer data exists;
- `SINGLE_CHOICE` has exactly one correct option;
- `MULTIPLE_CHOICE` has a valid correct-answer set;
- `TRUE_FALSE_MATRIX` has valid statement/correctness structure;
- `NUMERIC_FILL` has a valid four-character answer;
- malformed Markdown does not silently produce a different quiz.

The backend is authoritative even if the editor performs instant client-side validation.

Parser errors should be structured enough to map back to the source (question and preferably line/column).

## Quiz content lifecycle

Quiz content is edited as a draft.

When published, an immutable `QuizVersion` is created.

Editing a quiz after publication creates/updates a later draft. Existing published versions are not mutated.

## Offline export

A published quiz version can be rendered to DOCX for offline printing.

The roadmap includes generating multiple shuffled paper variants (for example, exam codes) and optional answer-key output.

Offline rendering must preserve the published quiz version rather than reading mutable draft content.
