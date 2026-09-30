# Quiz Authoring

Status: **Baseline v0.2 — Quiz Markdown and editor UX accepted**

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

The exact Excel/DOCX templates remain future work.

## Markdown-first editor

The primary manual-authoring experience is split into two panes:

- editable Quiz Markdown source;
- live quiz preview.

The accepted grammar is defined by
`docs/specifications/quiz-markdown-spec.md`.

The editor may provide instant validation and authoring assistance, but the
backend parser/validator remains authoritative.

## Supported question types

Quizopia supports:

- `SINGLE_CHOICE`
- `MULTIPLE_CHOICE`
- `TRUE_FALSE_MATRIX`
- `NUMERIC_FILL`

Question headers use:

```text
Câu <number> [<TYPE>]:
```

Question stems, A-D content, and optional `Lời giải:` content may span multiple
lines and may contain supported fenced code blocks.

## NUMERIC_FILL

Quizopia intentionally retains the fixed four-character answer format described
by `ADR-013-numeric-fill-format.md`.

The accepted Markdown form is:

```text
Đáp án: <exact-four-character-token>
```

The accepted character/normalization contract is defined in the Quiz Markdown
specification and must remain consistent across frontend validation, backend
validation, persistence constraints where appropriate, importers, and grading.

## Optional explanation

A question may contain one optional `Lời giải:` block after its answer
structure.

The explanation may span multiple lines and use the same accepted Markdown
content subset as the question and options, including fenced code blocks.

Explanation content is review-sensitive because it may disclose the answer.
Assessment delivery must not expose it to a learner until the configured review
policy permits answer/review content.

## Grammar-aware editor completion

The Quiz Markdown editor provides context-aware structural suggestions. This is
authoring assistance only; valid pasted/typed Markdown must not depend on using
autocomplete.

### Trigger boundary

Structural autocomplete is enabled only when:

- the caret is on a structural line at column 1; and
- the caret is outside a fenced code block.

It must not trigger merely because ordinary prose contains words such as
`Câu`, `Đáp án`, or `Lời giải`.

For example:

```text
Đâu là câu trả lời đúng?
```

must not trigger a `Câu` snippet.

### New-question snippets

When the teacher begins a structural line with any accepted prefix of `Câu`,
including:

```text
C
Câ
Câu
```

the editor immediately offers four full question snippets for the expected next
question number:

```text
Câu <n> [SINGLE_CHOICE]:
Câu <n> [MULTIPLE_CHOICE]:
Câu <n> [TRUE_FALSE_MATRIX]:
Câu <n> [NUMERIC_FILL]:
```

The suggestion UI should show a readable label plus the exact snippet.

Selection controls:

- `ArrowUp` / `ArrowDown` move the active option;
- `Tab` accepts;
- `Enter` accepts;
- single mouse click accepts;
- `Escape` closes the menu.

After accepting a question snippet, the caret is placed after the space
following the final colon:

```text
Câu 3 [MULTIPLE_CHOICE]: |
```

The proposed number is editor assistance only. Backend validation still enforces
continuous numbering.

### Type fallback completion

If the teacher manually types:

```text
Câu 3 [
```

the editor offers the four accepted type identifiers.

Choosing one completes the type plus closing bracket and colon:

```text
Câu 3 [NUMERIC_FILL]: |
```

Type completion must not trigger after an arbitrary `[` elsewhere in content.

### Option completion

For choice/matrix questions, when the current parser/editor state expects an
option at a structural line, the editor offers the expected label and its
correct/TRUE variant.

Example while expecting A:

```text
A.
*A.
```

After A, the corresponding completion advances to B, then C, then D.

The editor should filter suggestions using the current question state rather
than showing A-D indiscriminately.

For `SINGLE_CHOICE`, once one correct option exists, the UI may suppress or
de-emphasize additional `*` suggestions, but backend validation remains
authoritative.

### NUMERIC_FILL answer completion

`Đáp án:` is suggested only in the appropriate `NUMERIC_FILL` context and
only at a structural line.

Accepted prefix typing such as `Đ`, `Đá`, or `Đáp` may surface:

```text
Đáp án:
```

Acceptance produces:

```text
Đáp án: |
```

It must not be offered as an answer structure for the other three question
types.

### Explanation completion

`Lời giải:` is suggested only when:

- the current question's required answer structure is complete; and
- the question does not already contain an explanation.

Accepted line-start prefixes such as `L`, `Lờ`, or `Lời` may surface the
suggestion.

Acceptance produces:

```text
Lời giải: |
```

The editor must not suggest a second explanation for the same question.

### Code-fence behavior

Quiz structural autocomplete is disabled while the caret is inside a fenced code
block.

Text such as the following inside a code fence must be treated as code/content,
not editor structure:

```text
Câu 99 [SINGLE_CHOICE]:
*A. fake
Đáp án: fake
Lời giải: fake
```

### Accessibility and interaction

The suggestion popup must remain keyboard operable and must expose active
selection/state accessibly. Mouse support must not replace keyboard support.

## Validation before publication

Before publishing, validation includes at minimum:

- continuous question numbering;
- accepted question type;
- valid A-D ordering where required;
- non-blank question/option content;
- correct-answer cardinality;
- valid `NUMERIC_FILL` answer token;
- optional explanation position/cardinality;
- balanced supported code fences;
- malformed Markdown is rejected rather than guessed.

Parser errors should map to source locations, preferably question + line +
column.

## Quiz content lifecycle

Quiz content is edited as a mutable draft.

Teacher-authored source is preserved as written; saving must not rewrite it via a
canonical formatter.

Publishing parses and validates the draft, then creates an immutable
`QuizVersion`.

Published versions preserve immutable structured content and a source snapshot.
Later draft edits never mutate an existing published version.

## Offline export

A published quiz version may later be rendered to DOCX for offline printing.

The roadmap includes multiple shuffled paper variants and optional answer-key
output.

Offline rendering must use immutable published content rather than mutable draft
state.
