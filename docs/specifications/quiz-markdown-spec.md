# Quiz Markdown Specification

Status: **Accepted v1.0 — MVP grammar**

This file defines the accepted Quiz Markdown grammar for the MVP. Agents and
implementations must not replace the grammar with inferred legacy behavior or a
different Markdown convention.

## Goals

Quiz Markdown must be:

- easy for teachers to type;
- deterministic to parse and validate;
- precise about question type and answer semantics;
- friendly to live preview and grammar-aware editor assistance;
- capable of multiline question, option, statement, and explanation content;
- capable of fenced code blocks without interpreting code as quiz structure;
- safe to preserve as authored source while producing an immutable structured
  quiz representation for published versions.

## Structural recognition

Quiz structural markers have meaning only when both conditions hold:

1. the marker begins at column 1; and
2. the marker is outside a fenced code block.

Indented lookalike text is content, not structure.

Structural keywords use a colon:

```text
Câu 1 [SINGLE_CHOICE]:
Đáp án:
Lời giải:
```

Option/statement labels use a period:

```text
A.
*B.
C.
D.
```

A leading `*` marks a correct option or a TRUE statement.

## Question header — QM-01

Every question begins with exactly:

```text
Câu <number> [<TYPE>]: <optional first line of question content>
```

Accepted types are:

- `SINGLE_CHOICE`
- `MULTIPLE_CHOICE`
- `TRUE_FALSE_MATRIX`
- `NUMERIC_FILL`

The type identifier is case-sensitive.

Examples:

```text
Câu 1 [SINGLE_CHOICE]: HTTP là viết tắt của cụm từ nào?
```

and:

```text
Câu 2 [SINGLE_CHOICE]: Lan có 5 quả táo
Lan cho em trai Lan 3 quả
Lan ăn 1 quả
Hỏi Lan còn mấy quả?
```

Question numbering must be continuous and begin from 1 in source order.

## Multiline content blocks

Question stems, choice options/statements, and explanations are Markdown content
blocks and may contain multiple lines and paragraphs.

A block ends only when the parser encounters the next valid structural marker
for the current parser state, at column 1 and outside a fenced code block.

For example:

```text
*A. Dòng đầu tiên của phương án A
Dòng thứ hai vẫn thuộc phương án A.

Một đoạn khác vẫn thuộc phương án A.

B. Dòng này mới bắt đầu phương án B.
```

The same block rule applies to question stems and `Lời giải:`.

## Choice questions

The following types use exactly four ordered options/statements:

```text
A.
B.
C.
D.
```

Each option/statement must contain non-blank content.

### SINGLE_CHOICE

Validation:

- exactly A, B, C, D in order;
- exactly one option has a leading `*`.

Example:

```text
Câu 1 [SINGLE_CHOICE]: 2 + 2 bằng bao nhiêu?

A. 3
*B. 4
C. 5
D. 6
```

### MULTIPLE_CHOICE

Validation:

- exactly A, B, C, D in order;
- one or more options have a leading `*`.

Example:

```text
Câu 2 [MULTIPLE_CHOICE]: Chọn các giao thức tầng Application.

*A. HTTP
*B. FTP
C. TCP
D. UDP
```

### TRUE_FALSE_MATRIX

Validation:

- exactly A, B, C, D in order;
- `*` means the statement is TRUE;
- absence of `*` means the statement is FALSE;
- all four statements may be FALSE.

Example:

```text
Câu 3 [TRUE_FALSE_MATRIX]: Xác định tính đúng sai.

*A. HTTP thuộc tầng Application.
B. TCP thuộc tầng Application.
*C. HTTPS có thể sử dụng TLS.
D. UDP đảm bảo delivery.
```

## NUMERIC_FILL — QM-02 and QM-03

A `NUMERIC_FILL` question does not use A-D options.

Its answer syntax is exactly:

```text
Đáp án: <numeric-token>
```

The numeric token must be on the same line as `Đáp án:`.

Valid example:

```text
Câu 4 [NUMERIC_FILL]: Giá trị của 10 / 4 là bao nhiêu?

Đáp án: 2.50
```

The following form is invalid:

```text
Đáp án:
2.50
```

### Four-character answer

After trimming only surrounding whitespace from the answer token:

- the token length is exactly 4 characters;
- allowed characters are ASCII digits `0-9`, `-`, and `.`;
- at least one digit is required;
- `-` may appear at most once and only as the first character;
- `.` may appear at most once;
- `.` may not be the first or last character;
- internal whitespace is not allowed.

Valid examples:

```text
1234
0001
2.50
0.25
-3.5
-0.5
12.3
```

Invalid examples include:

```text
123
12345
+123
1,25
.250
250.
1..2
--12
1 23
１234
1e03
```

Normalization is intentionally minimal:

- trim surrounding whitespace only;
- do not convert comma to decimal point;
- do not remove `+`;
- do not convert full-width digits;
- do not pad/truncate;
- do not otherwise guess teacher intent.

Wave 3 [GRADE-05](assessment-core-policy.md) compares numeric answers by exact
decimal numerical equality without tolerance. The four-character grammar above
continues to govern the published correct-answer token. Student submission
syntax and validation remain a separate GRADE-07 contract gate; this authoring
grammar does not itself require Students to type four characters.

## Optional explanation block

Every question may contain zero or one `Lời giải:` block.

The marker may have content on the same line:

```text
Lời giải: TCP và UDP thuộc tầng Transport.
```

or introduce multiline content:

```text
Lời giải:
TCP và UDP không phải giao thức tầng Application.

HTTP và FTP là hai giao thức phổ biến ở tầng Application.
```

For choice questions, `Lời giải:` may occur only after D.

For `NUMERIC_FILL`, `Lời giải:` may occur only after the `Đáp án:` line.

A duplicate `Lời giải:` in one question is invalid.

Explanation content is part of the immutable published question content but is
review-sensitive. Learner APIs must not expose it before the applicable
Assessment review policy allows answer/review content.

## MVP Markdown content — QM-04

Question stems, options/statements, and explanations support:

- plain text;
- bold;
- italic;
- inline code;
- backtick fenced code blocks.

A fenced code block may include an optional language/info identifier.

Example:

````text
Câu 5 [SINGLE_CHOICE]: Cho đoạn code sau:

```java
int a = 5;
int b = 3;
System.out.println(a - b);
```

Chương trình in gì?

A. 1
*B. 2
C. 3
D. 4
````

Structural-looking text inside a fenced code block is content only:

````text
```text
Câu 99 [MULTIPLE_CHOICE]:
*A. fake
Lời giải: fake
```
````

An unclosed fenced code block is invalid source.

The MVP does not define support for:

- images;
- raw HTML;
- audio/video embeds;
- tables;
- footnotes;
- custom directives;
- Markdown links;
- other advanced nested rich-content structures.

These may be added later through an explicit compatible specification update.

### Proposed FE-05 math extension — pending project-leader approval

This section proposes extending the accepted MVP rendering subset; it is **not
yet an approved replacement** for v1.0. A question stem, option/statement body,
or explanation may contain inline math `$E = mc^2$` or display math enclosed in
`$$` delimiters, either on one line (`$$x^2$$`) or as a standalone multi-line
block with `$$` on separate lines. A paired `$...$` expression must be on one
line, have non-whitespace characters at both ends, and must not contain another
unescaped `$`. Standalone display delimiters must close before the block ends.
Unpaired/invalid delimiters and ordinary currency remain visible as text.

Math is content only: `authoringSource`, source offsets, correct-answer markers,
NUMERIC_FILL's exact four-character answer, and immutable published structured
question strings remain unchanged. This extension does not apply to metadata,
question-type tags, answer tokens, fenced code, inline code or escaped `\$`.
Only the display renderer interprets these delimiters. It must escape input,
disable trusted HTML/URL commands, limit macro expansion, and render invalid
math as literal source without executing code. Existing published strings remain
compatible without a schema-version bump. Both live and version-history preview
use the same renderer. Project-leader approval is required before merge.

## Source preservation — QM-05

Teacher-authored source is preserved as submitted.

Saving a draft must not:

- parse and re-render the source;
- canonicalize whitespace;
- reorder content;
- rewrite teacher formatting.

The draft therefore preserves the teacher's `authoringSource` while validation
produces a separate structured representation.

Publishing follows:

```text
QuizDraft authoringSource
        ↓
parser + validator
        ↓
structured immutable QuizVersion
```

A published `QuizVersion` should retain both:

- the immutable structured content used by downstream delivery/export; and
- the source snapshot from which that version was created.

A future canonical renderer for AI/import flows is tracked separately by
`QM-06` and must not overwrite manually authored source by implication.

## Parsing and validation

Malformed structure must be rejected rather than silently repaired.

Required validation includes:

- question numbering begins at 1 and is continuous;
- accepted case-sensitive question type;
- non-blank question stem;
- required A-D order for choice/matrix types;
- no duplicate/skipped option label;
- non-blank option/statement content;
- correct-answer cardinality;
- required `NUMERIC_FILL` answer;
- accepted four-character numeric token;
- explanation cardinality and position;
- balanced supported fenced code blocks;
- malformed structure does not silently produce a different quiz.

Parser errors must be structured enough for editor mapping and should include:

- error code;
- question number when known;
- line;
- column;
- human-readable message.

The backend parser/validator is authoritative even when the frontend performs
instant validation.

## Canonical example

````text
Câu 1 [MULTIPLE_CHOICE]: Chọn các giao thức tầng Application.

*A. HTTP
*B. FTP
C. TCP
D. UDP

Lời giải:
TCP và UDP không phải giao thức tầng Application.
HTTP và FTP là hai giao thức phổ biến ở tầng Application.

Câu 2 [SINGLE_CHOICE]: Lan có 5 quả táo
Lan cho em trai Lan 3 quả
Lan ăn 1 quả
Hỏi Lan còn mấy quả?

*A. 1
B. 2
C. 3
D. 4

Câu 3 [NUMERIC_FILL]: Cho biểu thức:

```text
10 / 4
```

Viết kết quả với hai chữ số sau dấu chấm.

Đáp án: 2.50

Lời giải:
`10 / 4 = 2.5`.
Theo định dạng yêu cầu, đáp án là `2.50`.
````

## AI/import interaction

Excel, DOCX, and AI import/generation should converge on the same structured quiz
domain model.

Whether machine-generated structured questions are rendered through a canonical
Quiz Markdown renderer remains `QM-06`.
