# Apply Context Pack v0.2 to an existing v0.1 repository

Context Pack v0.2 is designed as an **overlay** for the existing Quizopia 2.0 repository.

## Important preservation rule

Do **not** replace or delete your existing full file:

```text
docs/reference/legacy-analysis.md
```

The v0.2 ZIP deliberately omits that file so your corrected legacy analysis remains intact.

## Remove obsolete duplicate

Delete the old summary file if it still exists:

```text
docs/reference/legacy-summary.md
```

The full `legacy-analysis.md` is now the single canonical legacy reference.

## Apply

1. Extract the v0.2 ZIP.
2. Copy the contents of `quizopia-2-context-pack-v0.2/` into the root of `Quizopia_2.0/`.
3. Allow files to overwrite the v0.1 documentation.
4. Keep the existing `.gitattributes` already committed in your repository.
5. Keep the existing full `docs/reference/legacy-analysis.md`.
6. Delete `docs/reference/legacy-summary.md` if present.
7. Review the diff.

Suggested commands after copying:

```bash
git status
git diff --stat
git diff
```

Then commit:

```bash
git add .
git commit -m "docs: finalize Quizopia 2.0 pre-scaffold architecture"
git push
```

Expected result: `main` contains Context Pack v0.2 plus the full legacy analysis and your existing `.gitattributes`.
