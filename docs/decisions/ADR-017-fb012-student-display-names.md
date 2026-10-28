# ADR-017 — Student Display Names in Teacher Results (FB-012)

Date: 2026-10-28
Status: **ACCEPTED** (Leader approval with amendments, Issue #115 comment
6459795225; implemented on `feature/fb012-student-display-names`)

## Context

Teacher Results showed only `studentId` UUIDs. The product requires a
human-readable primary identifier. Identity owned no display name; only
`username` existed. Any solution required a new cross-service contract.

## Decision

1. **Identity owns an optional, user-provided `display_name`.** Nullable
   `user_account.display_name` (VARCHAR(100), migration V14, no backfill —
   legacy accounts are never assigned fabricated names). Self-service
   capture at registration and self-edit via
   `PUT /api/auth/me/display-name` (USER authority, own subject only,
   value never logged). Validation: NFC normalization → Unicode-aware trim
    → 1–100 Unicode code points → no ISO control characters or unpaired
    surrogates. It is NOT a verified legal
   identity. `/me` additionally exposes the raw nullable `displayName`.
   JWT claims, roles and token issuance are unchanged.

2. **Internal batch profile lookup.**
   `POST /internal/security/user-profiles/batch` (Identity), gated by the
   existing `/internal/security/**` chain: SERVICE principal + dedicated
   scope `identity.user-profile.read` + exact Assessment client subject →
   otherwise 403. Bounded 1–100 unique canonical UUIDs; strict transport
   checks (no query/Cookie/Transfer-Encoding/forbidden headers, 16 KB body
   cap). Response: `{items:[{studentId, displayName}]}` where `displayName`
   resolves `display_name ?? username` (approved fallback); unknown,
   deleted or disabled accounts are omitted; no email/roles/status;
   `Cache-Control: no-store`. Not exposed through the public Gateway.
    Default disabled (`quizopia.identity.user-profiles.enabled`).
    In addition to the 8-query concurrency cap, a fixed-memory rolling-minute
    request budget applies before body parsing/lookup, after strict caller
    authorization: default 60 requests/minute, configurable 1–6000 through
    `IDENTITY_USER_PROFILES_REQUESTS_PER_MINUTE`. All tokens of the sole exact
    Assessment caller share the instance-local budget; no user/token/request
    keys are retained. Excess requests return sanitized `429 PROFILE_RATE_LIMITED`
    with `no-store`. Malformed authorized calls count; unauthorized calls do not.
    This is per instance (replicas multiply capacity; restart resets budget),
    not a durable or cluster-wide quota.

3. **Assessment enrichment is fail-open and page-bounded.** After the
   existing owner-authorized page query commits (TEACHER role + publication
   ownership + page authorization), Assessment makes exactly ONE batch call
   for the current page's distinct student IDs (never pre-fetch, never
   cross-page reuse) through a dedicated
   `StudentProfileClient` (`identity.user-profile.read` client-credentials,
   ~2 s timeouts, strict JSON, defensive per-entry validation). Rows gain an
   additive nullable `studentName` (contract 1.4.0 → 1.5.0). Any Identity
   failure or unknown ID → `studentName: null`; authorized rows and scores
   are never removed or delayed. **Zero result-name cache** per the decision;
   a later cache (if ever measured necessary) must be identity/scope-safe
   and ≤ 30 s.

4. **Frontend fallback chain.** Shared results component renders
   `studentName` prominently with the UUID secondary; when null it renders
   the UUID alone with an explanatory accessible title. No fabricated names.
   Both embedded (`/app/assessments/{id}`) and standalone
   (`/app/assessments/{id}/results`) views share the same rows; vi/en
   labels included.

## Consequences

- Cross-service read-only profile surface is minimal and internal; least
  privilege enforced twice (scope + client-id subject restriction).
- Ownership checks strictly precede any profile lookup; non-owner requests
  never trigger Identity calls (verified by stub invocation counts).
- Pagination correctness verified: page-2 lookups contain exactly the
  page-2 distinct IDs.
- Assessment availability never depends on Identity profile availability.
- FB-015 (quiz deletion) remains NOT approved and is untouched.

## Backend contract verification

Owner sees names; other teacher blocked before lookup; wrong caller/scope
403; oversized/duplicate/malformed batch 400; unknown/deleted → UUID
fallback; Identity outage → rows retained with null names; paging preserves
identities; display-name updates flow through on next page load; no
email/roles/status leakage; `no-store` on sensitive responses.

Independent backend review: Identity full Maven `verify` passed 463 tests;
Assessment full Maven `verify` passed 442 tests (zero failures/errors/skips).
After adding the final query-count and response-size boundary checks, focused
`verify` passed 67 Identity tests and 61 Assessment tests, again with zero
failures/errors/skips. Both services passed Spotless using the isolated
`target/fb012-review` build directory. These include rolling-window expiration,
concurrent budget exhaustion, token-rotation resistance, sanitized no-store 429,
one query for 100 IDs and zero queries on rate rejection, Unicode/legacy-name
boundaries and invalid-page authorization before enrichment.

This backend evidence does not establish the complete Issue #118 acceptance:
real Gateway→Identity→Assessment browser journeys, both locales and both result
views still require separate integrated verification.

## Integrated verification (real stack)

- Frontend: 869/869 unit/RTL tests, strict TypeScript, ESLint, Prettier and a
  production build all green; a strict wire-schema regression test now pins
  the enriched row shape (`studentName` 1–255 chars, nullable) that the live
  stack exposed as missing.
- Real Gateway journey (no interception): 9/9 — student self-update `204`,
  `/me` exposes the name, owner results `200` with `no-store`, the display
  name is enriched into owner rows, clearing it falls back to the username,
  the internal batch endpoint is closed at the public edge (`403`), and a
  student token is denied teacher results (`403`).
- Real browser UI: 16/16 — standalone and embedded results show the
  self-provided name with the UUID secondary, in vi and en, at
  390/768/1440/1920 viewports, behind a real teacher login.
