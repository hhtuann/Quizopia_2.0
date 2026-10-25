# FE-01 / FE-02 frontend verification

Implemented on the T3 branch. The pre-existing user change in
`workspace-transition-overlay.tsx` (2500 to 1500 ms) is preserved untouched and
excluded from T3 commits. The initial working-tree results below retain that
local modification; the final isolated validation verifies the exact intended
T3 frontend with the reviewed T2 baseline.

## Final isolated and real-stack validation

All required frontend gates passed in the isolated checkout at
`C:\Users\GIGABYTE\AppData\Local\Temp\opencode\t3-isolated-20261026`:

- `pnpm test`: **779 passed**, 70 files.
- `pnpm typecheck`, `pnpm lint`, `pnpm format:check`, `pnpm build`: **PASS**.
- `pnpm test:e2e --config=playwright.isolated.config.ts --workers=2`:
  **109 passed** against a fresh production server on port 3317.
- Both OpenAPI contract generators' `--check` commands: **PASS**.

The isolated checkout includes every intended frontend change and excludes only
the unrelated local overlay edit. Integrity checks show that edit's before/after
diff is identical. Windows CRLF conversions in baseline/generated files were
normalized only in the temporary checkout before its formatting check.

Detailed validation report:
`C:\Users\GIGABYTE\AppData\Local\Temp\opencode\t3-validation-report-20261026.md`.

Real-stack evidence is separate from intercepted browser tests. Chromium used
the T3 Assessment, Classroom and Gateway jars with real Identity and Quiz, no
request interception, and an isolated production frontend on localhost:3000.
**63 assertions passed**, including reciprocal ownership isolation, signed
cursor rejection, real search/filters, CLASS card selection and DRAFT creation,
PUBLIC edit clearing classroomId, unsaved-navigation cancellation, Student
100.00% result and Teacher-results regression. Eight sanitized screenshots and
runtime/artifact hashes are under:
`C:\Users\GIGABYTE\AppData\Local\Temp\opencode\t3-live-20261026`.

The original live Teacher-results direct-load timeout is retained in that report;
directory-to-results navigation passed on recheck. Live positive CLOSED-row
rendering and UI Load More with more than 20 rows were not exercised; backend
and intercepted browser pagination/state coverage passed. No production release
acceptance is implied.

## Delivered behavior

- `/app/assessments`: owner directory, server title search, DRAFT/OPEN/CLOSED
  filters, opaque-cursor load more, safe metadata, responsive rows/cards,
  appropriate manage/results links and explicit asynchronous states.
- Teaching shell navigation and application-home discovery. Existing Teacher
  role and Teaching workspace gates enclose the directory and creation flows.
- CLASS create/edit: owned native-radio Classroom cards with bounded paging,
  no automatic selection, keyboard selection, touch-sized labels, errors/retry
  and zero-class guidance. Existing selection outside the current page resolves
  through the existing protected detail endpoint with resource/actor binding.
  Inaccessible selection blocks review; PUBLIC clears classroomId. Monitoring,
  confirmation and unsaved-work guards remain in the existing form.
- Types generated from canonical Assessment and Classroom OpenAPI; generation
  writes deterministic LF on Windows and supports `--check`.

Backend ownership, LD-05 admission, cursor signatures and results authorization
remain server responsibilities. The automated browser suite below intercepts
Gateway responses; the final real-stack verification above does not. Synthetic
test fixtures were created through real APIs; no membership orchestration,
runtime provisioning edits, secrets edits or existing-business-record changes
were performed.

## Initial working-tree checks (includes unrelated local overlay change)

| Command (from `frontend/`, except generators)                                                                                                                                                | Result                                               |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------- |
| `pnpm test`                                                                                                                                                                                  | 778 passed, 1 failed; 69 test files passed, 1 failed |
| `pnpm typecheck`                                                                                                                                                                             | PASS                                                 |
| `pnpm lint`                                                                                                                                                                                  | PASS                                                 |
| `pnpm format:check`                                                                                                                                                                          | PASS                                                 |
| `pnpm build`                                                                                                                                                                                 | PASS; includes `/app/assessments`                    |
| `pnpm test:e2e --workers=2`                                                                                                                                                                  | 104 passed, 2 failed, 3 did not run                  |
| `pnpm exec playwright test tests/e2e/t2-visual-evidence.spec.ts tests/e2e/assessment-publication.spec.ts tests/e2e/teacher-directories.spec.ts --workers=2 --output=test-results/t3-recheck` | 24 passed                                            |
| `python scripts/local/generate-publication-types.py --check`                                                                                                                                 | PASS                                                 |
| `python scripts/local/generate-classroom-types.py --check`                                                                                                                                   | PASS                                                 |
| `git diff --check`                                                                                                                                                                           | PASS                                                 |

Python checks used PyYAML installed into the approved temporary tooling directory
and supplied through PYTHONPATH; application dependencies and runtime were not
changed.

### Remaining full-suite failures

1. `components/shell/workspace-transition-overlay.test.tsx:100`: the existing
   test expects the overlay to remain visible under the former 2500 ms window;
   the preserved user edit sets that window to 1500 ms.
2. `tests/e2e/application-shell.spec.ts:534`: the existing browser test waits
   1500 ms and then asserts that the overlay remains visible. It fails with
   the preserved 1500 ms behavior, including on retry. Its serial successor
   does not run.
3. The latest full E2E run additionally observed a client-side application
   exception at the T2 390 px Teacher-home capture, followed by two serial
   successors not running. The complete T2 suite and both Publication suites
   subsequently passed in the 24-test recheck without code changes. An earlier
   full run passed this capture. This remains a full-run transient observation,
   not a claim that the latest full suite passed.

All new FE-01/FE-02 journeys passed in the full run and recheck. The older CLASS
Publication test now provides real-contract owned-Classroom list/detail fixtures
and waits for the saved selection to resolve before reviewing edits.

## Browser screenshot evidence

Real Chromium, production UI and authenticated executor with intercepted Gateway
contracts. Both populated directories and CLASS create forms were captured.
All four viewport checks assert no document-level horizontal overflow. Native
Space/ArrowDown selection and cancelled unsaved navigation run at every width.

Paths below are relative to `frontend/test-results/t3-recheck/`:

| Viewport    | Evidence directory                                                       | PNG files                                                |
| ----------- | ------------------------------------------------------------------------ | -------------------------------------------------------- |
| 1920 × 1080 | `teacher-directories-direct-b5b3a-room-selector-evidence-1920-chromium/` | `my-assessments-1920.png`, `classroom-selector-1920.png` |
| 1440 × 900  | `teacher-directories-direct-b2a98-room-selector-evidence-1440-chromium/` | `my-assessments-1440.png`, `classroom-selector-1440.png` |
| 768 × 1024  | `teacher-directories-direct-c7ed5-sroom-selector-evidence-768-chromium/` | `my-assessments-768.png`, `classroom-selector-768.png`   |
| 390 × 844   | `teacher-directories-direct-9efe5-sroom-selector-evidence-390-chromium/` | `my-assessments-390.png`, `classroom-selector-390.png`   |

Artifacts are local ignored test output. Test source regenerates them. Directory
and selector screenshots were visually inspected at desktop and mobile widths.
