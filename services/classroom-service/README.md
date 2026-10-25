# Classroom Service

## T3 FE-02 owned directory

`GET /classrooms?limit=20&cursor=...` is available through Gateway and independently
protected by Classroom. Both boundaries require a validated USER with JWT and
fresh authoritative LD-05 `TEACHER`; ADMIN does not imply Teacher. Admission
disabled/unavailable/untrusted remains fail-closed (503), without a cached verdict.

Response: `{ "items": [{ "id", "name", "subject", "grade", "description", "createdAt" }],
"nextCursor": "opaque-or-null" }` (field names shown schematically). `description`
and `nextCursor` may be null. No owner ID, memberships, invitations, student identity
or publication data is projected. Empty directories return `items: []` and
`nextCursor: null`. The collection contract is
[`contracts/classroom-directory.openapi.yaml`](contracts/classroom-directory.openapi.yaml);
Springdoc also publishes the implemented operation.

`limit` defaults to 20 and must be 1–100. The database query filters by the JWT actor
and fetches at most `limit + 1` safe projections, using immutable
`createdAt DESC, id DESC` keyset ordering. Metadata edits do not reorder entries;
newer insertions are visible on a first-page refresh. Pagination is not a multi-request
database snapshot. Invalid/foreign/tampered cursors and invalid limits return
400 `INVALID_REQUEST`. Treat cursors as opaque and pass them unchanged.

Cursor authentication uses HMAC-SHA256 with a purpose-separated key derived from
the service's existing `quizopia.admission.client-secret` / `USER_ADMISSION_CLIENT_SECRET`.
No new secret or node-local random key is required. Instances using the same
credential accept one another's cursors. Rotating that credential invalidates old
cursors (400); restart from the first page. An absent credential cannot issue a
directory response (503). Secret material is never included in cursors or errors.

V2 adds database-default, JPA-read-only `created_at` and the index
`(owner_teacher_user_id, created_at DESC, id DESC)`. Existing records receive V2's
migration time because their historical creation time was never stored; the UUID
tie-breaker keeps their order deterministic. Existing create/detail DTOs and
internal Teacher/member authority retain their contracts. No event change is needed.

Verification: Java 21 Maven `verify` in `target/t3-directory-verification` passed
133 tests, including PostgreSQL 17
owner/keyset, cursor integrity/foreign-owner, safe-field projection, admission failure
and existing create/detail/internal-authority regression coverage.

Independent Spring Boot 4.1.1 / Java 21 project for the Quizopia 2.0 platform scaffold.

Run independently from this directory:

- Windows: `mvnw.cmd test` or `mvnw.cmd verify`
- Unix-like shells: `./mvnw test` or `./mvnw verify`

Wave 1B Step 1 adds Classroom-owned domain and persistence foundations:
classrooms, memberships, and pending invitations. No concrete Identity or
RabbitMQ integrations are implemented.

The domain is plain Java, with application-owned repository ports and JPA
adapters in `persistence`. Ports provide insert and single-object lookup;
inserts reject duplicate IDs/unique keys rather than updating existing rows.
Future orchestration must own authorization, verified-account lookup, and
transport delivery of verified-account facts.

`V1__create_classroom_foundation.sql` creates three tables in `classroom_db`.
Memberships are unique by classroom/user; pending invitations are unique by
classroom/normalized email. Foreign keys reference only local classrooms.
User/teacher references are scalar UUIDs, with no Identity table dependency.
Flyway runs before Hibernate schema validation through Boot's Flyway starter.

Subject and grade remain required free-form text pending CLASS-03. Required
metadata has nonblank checks; text columns avoid arbitrary product length
limits. Email uses a 320-character storage bound consistent with Identity.
No timestamps, membership roles, status, expiry, soft-delete, or audit fields
are introduced in this step.

Invitation email normalization strips surrounding whitespace and lowercases
only the domain using `Locale.ROOT`. It preserves local-part case, dots, and
plus suffixes. Identity currently preserves email text and exposes no shared
normalization contract; this local representation does not establish a
cross-service matching contract. That contract must be agreed before lookup
or claim integration. Basic address shape checks do not establish mailbox
ownership or resolve ID-01 (Gmail versus Workspace domain eligibility).
Teacher-entered full name remains invitation metadata.

Wave 1B Step 2 adds application use cases for creating and retrieving an owned
classroom. Creation accepts the authenticated caller's internal user ID
separately from metadata and always uses it as the owner; creation input has no
owner field. Owner-only retrieval distinguishes missing classrooms from a
caller who does not own the requested classroom through application exceptions,
without choosing future HTTP status mappings. Creation uses a small injectable
ID generator and a random UUID component in production. The write use case is
transactional, and owner-checked retrieval uses a read-only transaction.
Role checks remain at the later security boundary, with no TEACHER or ADMIN
logic in this application layer.

Wave 1B Step 3 adds transport-neutral manual student-add orchestration. The
application-owned `VerifiedUserLookup` port accepts the email as supplied and
returns only a verified account's internal user ID; it defines no shared or
Identity-canonical email normalization. When a verified user is found, the
application creates a membership using exactly that returned ID. Otherwise it
creates a pending invitation containing the Classroom-local normalized email
and teacher-entered name, without creating a placeholder user.

The coordinator performs a short owner precheck, invokes the lookup with no
database transaction open, and delegates to a focused write transaction that
revalidates ownership before insertion. Database uniqueness constraints remain
the concurrency guard. The coordinator is intentionally constructed only when
a future concrete lookup adapter is available; Step 3 provides no default or
network implementation of that port.

Wave 1B Step 4 exposes the first two business HTTP operations:
`POST /classrooms` creates a classroom for a USER token carrying both
`TOKEN_USER` and `ROLE_TEACHER`, and `GET /classrooms/{classroomId}` returns a
classroom only to its owner and requires `TOKEN_USER`. The API derives the
caller's internal UUID from the accepted canonical JWT `sub`; create requests
cannot provide an owner. Subject and grade remain strings.

The Classroom Resource Server independently implements ADR-014 claim
validation without depending on Identity Java packages. USER and SERVICE
authorities remain disjoint, mixed or malformed claims fail authentication,
and a SERVICE token cannot reach the user-only Classroom operations. API and
security failures use the local `code`, `message`, `status`, and `path` error
shape. Springdoc publishes only the implemented create and owned-read
Classroom operations. Manual student addition remains transport-neutral and
has no HTTP endpoint because no accepted verified-user Identity transport
contract exists yet.

Wave 1B Step 5 adds a transactional application capability that accepts an
already-trusted verified-user UUID and email fact, claims every matching local
pending invitation across classrooms, establishes any missing memberships,
and consumes invitations after membership is satisfied. Repeated claims and
claims for an existing membership are successful and do not create duplicate
memberships. Membership user IDs come only from the trusted supplied UUID;
invitation names remain local metadata.

Claim lookup applies `NormalizedInvitationEmail` solely to match Classroom's
stored representation: surrounding whitespace is removed and only the domain
is lowercased, while local-part case, dots, and plus suffixes are preserved.
This is not a cross-service canonicalization contract. No production Identity
or RabbitMQ adapter is wired, and the final integration event schema remains
deferred until it is accepted.

Tests include plain JUnit domain checks, ArchUnit boundaries, a technical
smoke configuration without persistence, and full application persistence
tests using Testcontainers `postgres:17-alpine`. The latter use the
`persistence-test` profile (base configuration plus container properties),
apply Flyway on a clean database, and validate Hibernate mappings.

CLASS-01 through CLASS-04 remain open. Join-code format/rotation/expiry,
invitation delivery/expiry, membership roles, pagination/versioning, event
schemas/topology, and outbox implementation are deferred to later accepted
scope. No assignments, announcements, or gradebook state is included.

Default local port: 8083. See `.env.example` and the root development documentation for configuration.

## LD-05 admission boundary

See the [exact protected-route inventory and deployment gates](../../docs/architecture/ld05-enforcement.md).
Admission is independently disabled by default; selected Classroom operations
fail closed while disabled/unconfigured. Assessment and Proctoring business
routes remain denied until exact reviewed adapters exist. Provision this
deployment's own least-privilege SERVICE client and runtime secret. No Wave 3
business feature or Identity activation is enabled by this prerequisite.

Do not route activated protected traffic to bypass-capable older Gateway or
downstream instances. Drain/block old instances before activation or rollback.

## Private live-monitoring target membership

`POST /internal/classrooms/{classroomId}/proctoring-students/{studentId}/membership`
requires the exact configured Assessment SERVICE caller with
`classroom.proctoring-membership.read` and original independently verified/currently
admitted TEACHER evidence. One owned primary query checks Classroom ownership and
actual target membership in a read-write READ COMMITTED transaction; standby recovery
state fails closed. Both original Teacher and SERVICE expiry are rechecked after
the database response before a positive result. It returns only `classroomId`, `studentId`, `checkedAt`;
missing/nonowned/nonmember is masked 404 and unavailable authority is sanitized 503.
No Student authentication/account eligibility, roster, email or answer data is inferred.
The endpoint is private, bodyless/queryless, no-store and separately disabled by
`CLASSROOM_PROCTORING_MEMBERSHIP_ENABLED=false`. Existing membership/Teacher scopes
remain unchanged. Live monitoring also requires the separate current target Identity
check; this endpoint cannot substitute Teacher evidence for Student authentication.
Actual deployed private TLS/security acceptance and monitoring production approval
remain required.
