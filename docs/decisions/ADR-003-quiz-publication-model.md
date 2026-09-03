# ADR-003: Separate Quiz Content from Publication

Status: **Accepted**

## Context

A reusable quiz may be assigned to different classrooms, published publicly, used for practice, or delivered multiple times with different settings.

Copying the quiz for every delivery would duplicate content and mix authoring with delivery policy.

## Decision

Use:

```text
Quiz Draft
  -> immutable QuizVersion
      -> Publication
          -> Attempt / Practice Session
```

`QuizVersion` is immutable content.

`Publication` owns delivery configuration.

A single immutable QuizVersion may be used by multiple publications.

## Consequences

- publishing content and publishing a delivery are separate operations/concepts;
- old attempts remain historically correct;
- editing the library quiz does not mutate a delivered assessment;
- Assessment Service receives/stores a self-contained delivery snapshot needed for active attempts.
