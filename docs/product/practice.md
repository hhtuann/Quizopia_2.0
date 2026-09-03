# Practice Mode

Status: **Baseline v0.1**

## Purpose

Practice mode is intended for self-paced learning and quiz-card-style study.

It is distinct from assessment mode.

## Timing

Practice mode does not use:

- assessment availability window;
- assessment attempt duration.

If future scheduling is required for classroom practice, it must be designed explicitly rather than silently reusing assessment timing semantics.

## Learning flow

Typical flow:

1. show question;
2. learner answers;
3. provide allowed feedback;
4. show explanation when policy allows;
5. continue to next question.

Future enhancements may include learning-confidence controls such as Again/Hard/Good/Easy or spaced repetition, but these are not v0.1 requirements.

## AI tutor

Practice mode may expose an AI tutor.

Example learner interactions:

- "Give me a hint."
- "Explain this concept."
- "Why is option B wrong?"
- "Teach me this topic from the uploaded source."

The AI tutor must obey publication/learning policies and must not automatically leak hidden answers before the learner is allowed to see them.

Potential tutor policies include:

- hint-only;
- Socratic guidance;
- full explanation after answering.

Exact policy names and prompts are **TBD**.

## Attempt/result semantics

Practice sessions may track learning progress, but they are conceptually different from strict assessment attempts.

The final persistence model for practice progress is **TBD**.
