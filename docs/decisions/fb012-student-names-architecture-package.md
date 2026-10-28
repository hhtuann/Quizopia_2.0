# FB-012 / UX-11 — Student Names in Teacher Results: Architecture Decision Package

> **Status: BLOCKED FOR LEADER CONTRACT APPROVAL** — no new internal endpoint,
> scope or frontend enrichment is implemented. Teacher Results continue to
> show the authoritative student identifier; no names are fabricated.
>
> **Supersedes/extends** the Round-2 package
> (`ux11-student-names-architecture-package.md`, PR #114 branch) with the
> Round-3 (Issue #115) verification pass. Branch:
> `feature/ux-round3-assessment-workspace` (docs only).

## 1. Verified current state (Round-3 re-inspection)

- Identity `user_account` stores **`username` only** (V1 migration; V5 adds a
  unique non-null index). There is **no display-name / first-name /
  last-name column** — confirmed against all Identity migrations.
- Identity public API: `/me` (self only). Internal APIs: boolean eligibility
  probes only (`/internal/security/{notice-recipients,proctoring-students}/
{studentId}/eligability`). **No profile lookup of any kind exists.**
- Assessment results rows carry `studentId` UUIDs only; no name field.

**Product gap:** `username` (e.g. `localteacher4faedd1480`) is not a real
student name. If the product requires human names, Identity needs a
`display_name` column (registration capture or profile edit) FIRST —
otherwise the enrichment contract can only ever return usernames.

## 2. Proposed contract (unchanged core, tightened)

1. **Prerequisite (new decision):** add `display_name` to Identity accounts
   (nullable; populated at registration via a "Họ và tên / Full name" field
   with a migration backfill = username until edited). Without this, approve
   username-as-name explicitly.
2. Identity: `POST /internal/security/user-profiles/batch` — SERVICE JWT with
   new least-privilege scope `identity.user-profile.read`, caller restricted
   to the Assessment service client; request ≤100 unique `studentIds`;
   response `{items:[{studentId, displayName}]}`; unknown ids omitted;
   `Cache-Control: no-store`; no email/roles/status.
3. Assessment: after the existing owner-authorized results-page query,
   exactly ONE batch call for the page's ids; merge `studentName` (nullable,
   additive contract bump) into rows; fail-open to ids on Identity outage;
   no client-side mapping ever trusted.
4. Frontend: `studentName` primary, id secondary/mono, id-only fallback;
   both embedded and standalone results, both locales.

## 3. Security analysis (unchanged)

Internal-only route (no public edge), dedicated scope, ownership checked
before any lookup, bounded batches (no N+1 / enumeration), username/display
name only, no-store, fail-open preserves owner reads. Full threat table and
the 8-item security-test matrix remain in the Round-2 package.

## 4. Leader decisions requested

1. Approve `display_name` on Identity accounts (migration + registration
   field + optional profile edit) — **or** explicitly accept `username` as
   the displayed identifier.
2. Approve the `identity.user-profile.read` scope + internal batch endpoint.
3. Approve additive nullable `studentName` on Assessment result rows.
4. Confirm fail-open outage posture and ≤30s bounded name cache.

Until 1–3 are approved: FB-012 stays **BLOCKED**; both results views render
the authoritative identifier (unchanged this round).
