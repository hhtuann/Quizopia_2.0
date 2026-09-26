# Classroom Service

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
