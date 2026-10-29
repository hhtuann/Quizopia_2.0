# UX-11 — Student Display Names in Teacher Results: Architecture Decision Package

> **Status: BLOCKED FOR LEADER CONTRACT APPROVAL** — a new cross-service
> authorization contract is required; none exists today. No implementation is
> merged. Production rollout remains NO-GO; BR-01 remains PENDING.
>
> **Origin:** Round 1 UX-05 investigation (paused) elevated by the Round 2
> scope amendment to a mandatory product requirement with an explicit
> architecture-approval gate. Package prepared on
> `feature/frontend-attempt-workspace-polish`.

## 1. Problem statement

Teacher Results (standalone `/results` and the embedded unified-detail section)
currently display the authoritative `studentId` UUID as the learner's only
identifier. The product requires the student **display name** as the primary
identifier, with the UUID secondary only where needed and a safe fallback.

## 2. Current-state facts (source-verified)

| Concern                    | Verified fact                                                                                                                                                                            |
| -------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Identity account model     | `user_account` has `username` (unique, non-null per V5) and email; **no separate display-name/full-name column exists**. The only human-readable name Identity owns today is `username`. |
| Identity public API        | `/me` returns the caller's own `{id, username, email, roles}` only (USER JWT).                                                                                                           |
| Identity internal API      | `POST /internal/security/{notice-recipients                                                                                                                                              | proctoring-students}/{studentId}/eligibility` — boolean eligibility only (SERVICE JWT, admission scopes); **no profile/name lookup of any kind**. |
| Assessment result contract | Result rows carry `studentId: uuid` only; no name field anywhere in `publications.openapi.yaml`.                                                                                         |
| Cross-service auth         | SERVICE JWT (OAuth2 client credentials) with least-privilege scopes; Assessment already holds `identity.user-admission` client scope used for eligibility checks.                        |
| Frontend                   | Teacher Results render `studentId` verbatim (both locales, both standalone and embedded views).                                                                                          |

Therefore: **no authorized mechanism exists** that can satisfy the
requirement. Any implementation without a new contract would require either a
public profile endpoint (enumeration risk), client-supplied names (untrusted),
or cross-service database access (prohibited by AGENTS.md hard constraints).

## 3. Proposed contract (for approval, not implemented)

### 3.1 Identity: bounded internal batch profile lookup

```
POST /internal/security/user-profiles/batch
Authorization: Bearer <Assessment SERVICE JWT, scope identity.user-profile.read>
Content-Type: application/json
Cache-Control: no-store (response)

Request  { "studentIds": ["<uuid>", ...] }        // 1..100 items, unique
Response 200 { "items": [ { "studentId": "<uuid>", "username": "<string>" } ] }
         // Unknown/deleted ids are OMITTED, never errored, never null-filled.
Errors:  400 INVALID_REQUEST (bad shape/duplicates/>100), 401/403 (wrong
         caller/scope), 503 (Identity unavailable → see 3.4)
```

- **Caller identity:** only the registered Assessment service client
  (`quizopia-local-assessment` locally; equivalent per environment). No USER
  JWT is ever accepted on this route; no public edge route exists.
- **New scope:** `identity.user-profile.read` — least privilege, added only to
  the Assessment client registration (follows the existing admission-scope
  provisioning pattern; requires gateway/service route inventory + allowlist
  updates and their tests).
- **Field exposure:** `username` only — no email, no roles, no account status,
  no credentials, no profile metadata. If a real display-name column is
  introduced later, this contract is the single enrichment point.
- **Batching bound:** hard limit 100 ids/request, deduplicated, enforced by
  validation; prevents unbounded or N+1 enumeration patterns.

### 3.2 Assessment: owner-authorized enrichment

- Enrichment happens **only after** the existing owner-authorized results-page
  query returns its bounded page (≤ `limit+1` rows); the service then makes
  **one** batch call for exactly the ids on that page.
- Owner authorization, cursoring, filters and row shapes are unchanged; the
  row gains an optional `studentName: string | null` (null when omitted by
  Identity).
- `Cache-Control: no-store` retained on results responses.
- Response contract version bump (non-breaking, additive field) with generated
  frontend types regenerated from OpenAPI.

### 3.3 Frontend presentation

- `studentName` renders as the primary identifier; `studentId` collapses to a
  secondary/monospace detail (or title attribute) only where genuinely needed.
- Fallback: when `studentName` is null/missing, the existing `studentId`
  display remains — **no fabricated names, ever**.
- Both locales (vi/en) and both views (standalone `/results` and embedded
  unified-detail section) share one row renderer.

### 3.4 Failure semantics (fail-safe)

- Identity unavailable (503/timeout): Assessment serves the results page
  **unchanged with ids only**; enrichment never blocks, retries or fails the
  owner's authorized read. A short-lived in-process cache (≤30s, bounded by
  page size) may suppress repeat lookups within one browsing session but is
  never a durable store.
- Deleted/unknown users: omitted by Identity → null name → id fallback.

## 4. Security analysis

| Threat                      | Mitigation                                                                                                                  |
| --------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| Public profile enumeration  | Internal-only route; SERVICE JWT + dedicated scope; no public edge route; batch bound 100.                                  |
| Cross-teacher data leakage  | Enrichment strictly after Assessment's existing ownership check; only ids already authorized to that teacher are ever sent. |
| Personal-data over-exposure | `username` only; no email/roles/status; no-store.                                                                           |
| Unbounded/N+1 lookups       | Single bounded batch per results page; no per-row calls.                                                                    |
| Identity outage             | Fail-open to id-only rendering (no data loss, no block).                                                                    |
| Auditability                | Existing structured service logs record the batch call shape (count only, no names) with trace ids.                         |

## 5. Required security/contract tests (on approval)

1. Authorized Teacher sees names on owned results (standalone + embedded, vi + en).
2. Unauthorized Teacher cannot trigger enrichment for unrelated students
   (403 before any Identity call).
3. Missing/deleted user → id fallback, no error surfaced.
4. Identity 503/timeout → id-only page, owner read still 200.
5. Pagination consistency: names stay aligned with cursor pages.
6. Wrong caller/scope on the internal route → 401/403 (Identity integration test).
7. Batch >100 / duplicates → 400; response carries no email/role fields
   (schema test).
8. Gateway/service route inventories updated and tested (exact-path admission).

## 6. Explicit Leader decisions requested

1. Approve the new `identity.user-profile.read` scope and the internal batch
   endpoint contract above (or mandate a real display-name column first).
2. Approve the additive `studentName` field on Assessment result rows.
3. Confirm the fail-open-to-ids outage posture and the ≤30s bounded name cache.
4. Confirm `username` is the acceptable display-name source until a profile
   display-name exists.

Until approval: Teacher Results continue showing authoritative identifiers;
no names are fabricated and no insecure shortcut is introduced.
