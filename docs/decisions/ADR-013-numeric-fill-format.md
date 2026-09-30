# ADR-013: Preserve the Four-Character NUMERIC_FILL Format

Status: **Accepted**

## Context

The Quizopia 1.x legacy analysis recommended removing the fixed four-character
`NUMERIC_FILL` limitation because it is restrictive for general numeric-answer
systems.

Quizopia 2.0 has an explicit product requirement to retain the four-character
answer format to match the Vietnamese Ministry-of-Education-aligned question
style targeted by this project.

## Decision

Quizopia 2.0 deliberately preserves:

```text
NUMERIC_FILL correct answer length = exactly 4 characters
```

The accepted Quiz Markdown syntax is:

```text
Đáp án: <numeric-token>
```

The token must be on the same line as `Đáp án:`.

After trimming surrounding whitespace only:

- the token length is exactly four characters;
- allowed characters are ASCII digits `0-9`, `-`, and `.`;
- at least one digit is required;
- `-` may appear at most once and only as the first character;
- `.` may appear at most once and may not be first or last;
- internal whitespace is invalid.

No additional normalization is performed. In particular the system does not
convert comma decimal separators, remove a plus sign, convert full-width digits,
pad/truncate, or otherwise guess teacher intent.

Examples accepted by the format include:

```text
1234
0001
2.50
0.25
-3.5
-0.5
12.3
```

This accepted 2.0 requirement overrides the legacy recommendation to remove the
four-character constraint.

## Consistency requirement

The same representation rule must be implemented consistently in:

- Quiz Markdown parser;
- frontend editor validation;
- backend domain validation;
- Excel import when implemented;
- DOCX import when implemented;
- database constraints where appropriate;
- grading.

The complete authoring grammar is defined in
`docs/specifications/quiz-markdown-spec.md`.

## Still open

This ADR does not define the learner-answer comparison/scoring algorithm.

Exact grading comparison/normalization remains tracked by `GRADE-05` and must
be finalized before Assessment grading implementation.
