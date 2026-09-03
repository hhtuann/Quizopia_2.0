# Git Workflow

Status: **Baseline v0.1**

## Branches

Recommended long-lived branches:

- `main` — stable/releasable history;
- `develop` — integration branch while the team uses Git Flow-style development.

Task branches:

- `feature/...`
- `fix/...`
- `refactor/...`
- `docs/...`
- `chore/...`

Do not create permanent branches per person.

## Unit of work

Prefer:

```text
1 GitHub Issue
-> 1 task branch
-> 1 primary implementation agent/developer
-> 1 PR
```

Avoid multiple agents concurrently rewriting the same branch/files.

## Example

```text
feature/identity-email-otp
feature/classroom-manual-invite
feature/quiz-markdown-parser
feature/community-contribution-post
```

## PR flow

Typical:

```text
feature/*
    -> PR
develop
    -> release/stabilization
main
```

Protect `main`.

Consider protecting `develop` with required PR checks as the project grows.

## Agent review

Recommended:

1. Agent A implements.
2. Agent B performs read-only/code-review analysis.
3. Agent A/developer fixes findings.
4. Human performs final review.
5. Merge after tests/checks pass.

The reviewer agent must not silently redesign the feature outside the issue/spec.

## Commit expectations

Use focused commits.

Architecture/product changes must update relevant docs/ADR in the same PR or a linked prior PR.

## Merge criteria

A PR should not merge when:

- relevant tests fail;
- accepted business behavior is undocumented;
- service boundaries are violated;
- migration changes are missing;
- API/event contract changes are undocumented;
- security-sensitive behavior lacks review.
