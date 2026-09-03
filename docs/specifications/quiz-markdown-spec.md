# Quiz Markdown Specification

Status: **Draft v0.1 — grammar not fully finalized**

This file intentionally distinguishes already-agreed behavior from unresolved syntax.

## Goals

Quiz Markdown must be:

- easy for teachers to type;
- easy for AI/importers to generate;
- deterministic to parse;
- precise enough to validate question type and answers;
- safe to round-trip between editor and canonical quiz model;
- able to produce live preview.

## Agreed lexical conventions

### Question start

A new question begins with:

```text
Câu <number>.
```

Example:

```text
Câu 1. Nội dung câu hỏi
```

The parser must validate sequence order.

### Options/statements

Option/statement labels use:

```text
A.
B.
C.
D.
```

Order must be validated where the question type requires A-D structure.

### Correct marker

A leading `*` marks a correct option/statement.

Example:

```text
*B. phương án đúng
```

## Question types

Required supported types:

- `SINGLE_CHOICE`
- `MULTIPLE_CHOICE`
- `TRUE_FALSE_MATRIX`
- `NUMERIC_FILL`

## NUMERIC_FILL

The answer must be exactly four characters under the final allowed-character rules.

## Unresolved type grammar

The simple notation below is not enough to unambiguously distinguish all question types:

```text
Câu 1. ...
A. ...
*B. ...
C. ...
D. ...
```

A final explicit type marker is therefore still **TBD**.

Candidate syntax proposed during design:

```text
Câu 1 [SINGLE_CHOICE]. ...
Câu 2 [MULTIPLE_CHOICE]. ...
Câu 3 [TRUE_FALSE_MATRIX]. ...
Câu 4 [NUMERIC_FILL]. ...
```

This candidate is **not yet an accepted grammar requirement**.

## Candidate canonical examples

### SINGLE_CHOICE

```text
Câu 1 [SINGLE_CHOICE]. HTTP là viết tắt của cụm từ nào?

A. Hyper Transfer Text Protocol
*B. HyperText Transfer Protocol
C. High Transfer Text Protocol
D. HyperText Translate Protocol
```

Validation:

- exactly one correct option;
- expected A-D structure.

### MULTIPLE_CHOICE

```text
Câu 2 [MULTIPLE_CHOICE]. Chọn các giao thức tầng Application.

*A. HTTP
*B. FTP
C. TCP
D. UDP
```

Validation:

- one or more correct options;
- expected A-D structure.

### TRUE_FALSE_MATRIX

```text
Câu 3 [TRUE_FALSE_MATRIX]. Xác định tính đúng sai.

*A. HTTP thuộc tầng Application.
B. TCP thuộc tầng Application.
*C. HTTPS sử dụng TLS.
D. UDP đảm bảo delivery.
```

Candidate meaning:

- `*` statement = TRUE;
- no `*` = FALSE.

The final display wording and parser rules must be confirmed.

### NUMERIC_FILL

Candidate:

```text
Câu 4 [NUMERIC_FILL]. Giá trị của biểu thức ... là bao nhiêu?

Đáp án: 2.50
```

Final answer-key keyword/syntax is **TBD**.

## Save/continue validation

Backend must reject invalid source with structured errors.

Required validations include:

- continuous question numbering;
- valid question-type syntax;
- option order;
- correct-answer cardinality;
- required answer data;
- NUMERIC_FILL four-character rule;
- malformed structure.

Recommended parser error fields:

- error code;
- question number;
- line;
- column;
- human-readable message.

## AI/import interaction

Excel, DOCX, and AI import/generation should converge on the same canonical quiz domain model.

Canonical Markdown rendering from structured data is a proposed strategy, but the team must finalize the grammar first.
