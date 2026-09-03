# ADR-013: Preserve the Four-Character NUMERIC_FILL Format

Status: **Accepted at requirement level**

## Context

The Quizopia 1.x legacy analysis recommended removing the fixed four-character NUMERIC_FILL limitation because it is restrictive for general numeric-answer systems.

Quizopia 2.0 has an explicit product requirement to retain the four-character answer format to match the Vietnamese Ministry-of-Education-aligned question style targeted by this project.

## Decision

Quizopia 2.0 deliberately preserves:

```text
NUMERIC_FILL correct answer length = exactly 4 characters
```

This accepted 2.0 requirement overrides the legacy recommendation.

## Consistency requirement

The same rule must be implemented consistently in:

- Quiz Markdown parser;
- frontend editor validation;
- backend domain validation;
- Excel import;
- DOCX import;
- database constraints where appropriate;
- grading.

## Still open

This ADR does **not** yet define:

- the exact allowed character set;
- decimal separator rules;
- sign rules;
- whitespace/canonicalization;
- final Markdown answer syntax;
- exact grading normalization.

Those remain feature-level questions and must be finalized before implementation.
