# Git Workflow

Status: **Accepted baseline v0.2**

## Long-lived branches

- `main` — stable/releasable baseline;
- `develop` — team integration branch once scaffold/team development begins.

Do not create permanent branches per person.

## Task branches

Examples:

- `feature/...`
- `fix/...`
- `refactor/...`
- `docs/...`
- `chore/...`

Preferred unit:

```text
1 issue
-> 1 focused branch
-> 1 primary implementer/agent
-> 1 PR
```

## Pull requests

Typical flow:

```text
feature/* -> develop -> main
```

Protect `main`.

Protect `develop` with required checks once team development starts.

## CI strategy

### Pull request

Use path-aware/targeted checks.

Examples:

- change `services/quiz-service/**` -> run Quiz Service checks;
- change `frontend/**` -> run frontend checks;
- change shared contracts -> run affected consumers;
- architecture/root build changes may trigger broader validation.

### `develop` / `main`

Run comprehensive repository validation.

## Agent workflow

Recommended:

1. Agent/developer implements.
2. A second agent performs review where useful.
3. Findings are fixed.
4. Human performs final review.
5. Merge only after required checks pass.

Avoid multiple agents concurrently rewriting the same branch/files.

## Documentation

Any PR that changes accepted product behavior, architecture, API/event semantics, security boundaries, or service ownership must update docs/ADR in the same change or a preceding accepted change.
