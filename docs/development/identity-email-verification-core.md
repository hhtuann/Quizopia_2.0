# Identity email verification core (Wave 1A Step 10 and Wave 2A Task 3)

The production request boundary is `EmailVerificationRequestService`:

```java
request(UUID userId)
```

It owns OTP generation and the accepted production policy. Eligibility,
cooldown, and hourly-limit checks run before generation. It returns
`REQUEST_ACCEPTED` only after the transaction containing the hashed challenge,
issuance-history row, and encrypted email-outbox job commits. This means durable
acceptance for later delivery; SMTP is not called by issuance. The Identity
outbox dispatcher claims committed work in a short PostgreSQL transaction and
performs SMTP only after that transaction completes.

Wave 2A Task 3C provides a thin HTTP boundary over the established application
operations. `POST /api/auth/register` accepts `username`, `email`, and
`password`, and returns `202 {"status":"VERIFICATION_REQUIRED"}`. Both
verification endpoints use the exact stored username because duplicate pending
accounts may share an email: `POST /api/auth/email-verification/request` accepts
`username` and always returns `202 {"status":"VERIFICATION_REQUEST_ACCEPTED"}`;
`POST /api/auth/email-verification/confirm` accepts `username` and a six-digit
`otp`, then returns `204` on activation. All expected confirm failures collapse
to `400 AUTH_VERIFICATION_FAILED`; request no-ops and throttling remain a generic
202. Registration validation stays an invalid request; username and other
registration conflicts map to `409 AUTH_USERNAME_UNAVAILABLE` and
`409 AUTH_REGISTRATION_FAILED`. These exact `POST` routes are anonymous, while
the remaining Identity routes retain their existing authentication requirement.

The public lower-level confirmation boundary remains `EmailVerificationService`:

```java
verify(UUID userId, RawEmailVerificationOtp rawOtp)
```

Deterministic persistence tests use a test-source-only facade over the
package-private transaction seam. Production issuance is available only through
`EmailVerificationRequestService`; a production caller cannot supply OTP
material or challenge policy. This work has no HTTP endpoint, login, or token
issuance. Results contain neither raw OTP material nor hashes.

`SecureEmailVerificationOtpGenerator` uses `SecureRandom` to generate values in
the range 0 through 999999 and zero-pads them to exactly six ASCII decimal digits.
`RawEmailVerificationOtp` enforces `[0-9]{6}` and redacts its diagnostic
representation. Only the existing Identity `serviceClientPasswordEncoder`
receives the raw value for encoding/matching. The challenge receives only that
encoded hash. The same transient OTP is separately encrypted by the Identity
outbox cipher for later recovery. The existing encoder also serves local
registration.

`EmailVerificationPolicy.production()` is fixed at a 60-second expiry (FE-09 proposal requiring leader approval before merge), five
failed attempts, and a 60-second resend cooldown. Lower-level tests may still
construct explicit policies to verify boundary behavior. Registration accepts an
exact stored address only when the domain substring is exactly `gmail.com`; it
does not trim, lowercase, remove plus aliases, or apply Gmail dot normalization.

## Persistence and lifecycle

V6 adds only `email_verification_challenge`. Its primary key and foreign key are
`user_id`, referencing Identity's `user_account`. It stores `otp_hash`,
`issued_at`, `expires_at`, `resend_not_before`, `failed_attempts`, and
`max_attempts`. Database checks enforce nonblank encoded material, valid attempt
bounds, positive lifetime, and a cooldown boundary at or after issuance. It has
no delivery state. V7 owns verified-email uniqueness. V8 adds
`email_verification_issuance_guard` and `email_verification_issuance` for the
authoritative issuance limit. The issuance table stores only exact email,
issuance timestamp, and an opaque row ID; it never stores OTP material.
V9 adds `email_verification_email_outbox`. Each issuance ID has at most one job.
The job stores recipient/template/expiry and delivery metadata plus AES-256-GCM
ciphertext, a 96-bit nonce, key version, and payload-format version. It stores no
plaintext OTP or rendered message. Pending/claimed jobs require encrypted
payload; terminal states require it to be cleared. V10 links each active outbox
job to its `user_account` so the dispatcher uses the correct username when
duplicate pending accounts share an email. Migration stops explicitly when a
legacy pending or claimed V9 job exists because email alone cannot identify its
account. The blocked migration leaves state, ciphertext, nonce, and claim data
unchanged. Terminal legacy jobs may retain a null account link.

V11 adds a nullable `current_issuance_id` association to the challenge. New
issuances always populate it, which lets the dispatcher prove immediately before
SMTP that the claimed job still belongs to the account's current challenge.
Legacy challenges remain valid for confirmation but their unlinked jobs fail
closed as obsolete delivery work.

`OutboxPayloadCipher` and `OutboxPayloadKeyRing` isolate application orchestration
from runtime secret injection. Configured keys are Base64 values that must decode
to exactly 32 bytes. Startup requires a valid active version present in the key
ring. Encryption uses a fresh secure nonce and the version-1 canonical binary
AAD encoding documented in the authentication architecture. The encrypted
plaintext is exactly the six ASCII OTP digits. OTP expiry is canonicalized to
PostgreSQL microsecond precision before it is encrypted and persisted, so the
dispatcher can reconstruct identical AAD after restart. Decryption distinguishes
missing historical keys, authentication failure, and other sanitized failure
without including payload or key material in diagnostics.

`EmailVerificationOutboxDispatcher` consumes the exact V9 format. It rebuilds
the existing `OutboxPayloadBinding`, including the PostgreSQL-microsecond expiry,
so the cipher remains the only canonical AAD serializer. The PostgreSQL store
claims `PENDING` due work and stale `CLAIMED` work with `FOR UPDATE SKIP LOCKED`,
sets a unique claim owner and lease, and commits before dispatcher processing.
Each external delivery attempt is durably reserved before decryption or SMTP by
incrementing `attempt_count` while the claim is still owned. This makes the
attempt ceiling survive a crash after SMTP acceptance but before the terminal
state update. During blocking SMTP, a dedicated heartbeat conditionally extends
the lease for the current claim owner; it stops when delivery returns. Final
updates still require the same claim owner.

The provider-neutral `VerificationEmailDelivery` port receives only the exact
recipient, exact username, redacted OTP value object, and persisted expiry. The Spring Mail
adapter renders one Identity-owned plain-text template in memory and sends it
with runtime SMTP configuration. Local development points the same adapter at
Mailpit. Generic or unclassified mail transport failures are treated as
transient; authentication and message parsing/preparation failures are
permanent. Only sanitized categories are persisted.

Retryable SMTP or missing-key failures consume the already reserved attempt and
use exponential backoff from 30 seconds, capped at five minutes by default. Five
attempts is the default operational ceiling. A retry is never scheduled at or
after OTP expiry.
Authentication-tag failure and other corrupt payload failures terminate without
SMTP. `SENT`, `FAILED`, and `EXPIRED` clear ciphertext, nonce, and claim data.
Lease recovery provides at-least-once delivery, so a crash after SMTP acceptance
may resend the same committed OTP.
V1–V5 are unchanged.

Replacing a challenge terminalizes its older active outbox work and clears the
recoverable payload. Successful verification does the same for remaining work
for that account. Establishing verified-email ownership also terminalizes active
work for losing pending accounts that use the same exact email. The dispatcher
coordinates its final current-issuance check and SMTP start with issuance and
activation through an exact-email PostgreSQL advisory send fence. The dispatcher
holds a session advisory lock from the final deliverability check through SMTP
and its state transition. Issuance and activation acquire the matching
transaction advisory lock before changing current delivery eligibility. If a
mutation commits first, the later dispatcher check rejects the obsolete job. If
the dispatcher acquires the fence first, the mutation cannot commit until that
delivery attempt and finalization finish. SMTP therefore remains outside a JPA
transaction and outside row locks while the send-start ordering is still
authoritative across service instances.

Dispatcher coordination uses a dedicated three-connection JDBC pool against the
same Identity PostgreSQL database and runtime credentials. The dispatcher is
locally single-flight, so the pool reserves one connection for the session
advisory fence, one for the lease heartbeat, and one for a concurrent outbox
state operation. JPA, Flyway, issuance, and activation continue using the
primary transactional pool. Transactions waiting on the exact-email fence
therefore cannot starve the live dispatcher's heartbeat or terminal update.

An issuance creates or replaces the single current challenge. It derives both
time boundaries from the injected server `Clock` and explicit policy, and resets
failed attempts to zero. The production Clock is `Clock.systemUTC()`. No caller
can pass a current-time argument. Decisions read time after acquiring the locks.

If `now < resend_not_before`, issuance returns `COOLDOWN` and leaves every field
unchanged. At or after that boundary, a replacement uses the newly supplied OTP
and policy. Only the replacement hash is retained. Expired/exhausted challenges
can likewise be replaced once their cooldown permits it.

After cooldown validation, issuance applies a rolling window `(now - 1 hour,
now]` with a maximum of five successful issuances for the exact stored email.
An issuance exactly one hour old is excluded. Issuance rows remain durable while
their outbox jobs refer to them; the indexed count query applies the time bounds
instead of deleting old rows. Rejected cooldown or hourly-limit requests do not
replace the current challenge. Issuance returns `ISSUED`,
`COOLDOWN`, `HOURLY_LIMIT`, `ALREADY_VERIFIED`, `NOT_FOUND`, or `CONFLICT`.

Verification first checks authoritative account state, then challenge presence,
expiry, attempt exhaustion, and finally `PasswordEncoder.matches`. Its outcomes
are `VERIFIED`, `INVALID_OTP`, `EXPIRED`, `ATTEMPTS_EXHAUSTED`,
`NO_ACTIVE_CHALLENGE`, `ALREADY_VERIFIED`, `NOT_FOUND`, and `CONFLICT`.

Fast confirmation failures perform one dummy match with the same OTP encoder cost
as a real challenge check. This reduces the strongest CPU timing distinction
without creating fake persistence or delivery. Verification-request no-ops do
not perform dummy BCrypt encoding: unauthenticated requests have no account-based
rate limit at that point, so adding expensive attacker-controlled work would
create a CPU denial-of-service primitive. Database, encryption, and write paths
still differ, so the public response equality is enumeration hardening rather
than a constant-time guarantee.

At `now >= expires_at`, verification returns `EXPIRED` without modifying the
challenge. Expiry takes precedence if a challenge is also exhausted. A mismatch
increments and persists the failed-attempt counter. The mismatch reaching the
limit returns `ATTEMPTS_EXHAUSTED`; subsequent attempts cannot match or increment
past the limit. Failed attempts do not change time boundaries.

## Transactions and concurrency

Both transaction methods lock the user row first, then any existing challenge
row, using PostgreSQL pessimistic write locks. Issuance and activation acquire
the exact-email send fence before the email issuance guard. Issuance then creates
or locks the exact-email guard row before counting and recording history. This serializes
issuances across duplicate pending accounts that share an email, while the user
lock serializes each account's challenge. The primary key additionally enforces
a single current challenge. No JVM synchronization or controller memory is used.

For a correct, eligible OTP, `EmailVerificationTransaction` calls the existing
Step 9 `TrustedEmailActivationTransaction` inside the same Spring transaction.
It reacquires the already-held user lock and performs the approved activation and
additive STUDENT persistence. Step 10 contains no copy of that transition/role
logic. It deletes and flushes the current challenge only after activation
succeeds. Any persistence exception rolls back the complete transaction.

Repeated verification reads the active, verified account and returns
`ALREADY_VERIFIED`, preserving the original timestamp. An active account without
a verification timestamp, pending account with one, or unsupported lifecycle
state returns `CONFLICT` without repair. Unknown users are never created. An
already-verified account with an existing challenge is left untouched.

Verification has no provider identity, refresh session/token, revocation,
service-client, Redis, event, or delivery side effects. Successful verification
changes only the approved account activation fields/role and removes its
challenge. Request issuance has no network side effect. Encryption or outbox
persistence failure rolls back the challenge, issuance record, and outbox job,
so the failed transaction consumes no hourly-limit slot.

## Validation

`EmailVerificationIntegrationTest` uses PostgreSQL 17 Testcontainers with Flyway
and Hibernate validation, explicit policy fixtures, and a controlled Clock. It
covers hash-only challenge storage, encrypted outbox recovery, production
request policy, atomic rollback, schema constraints, account-state handling,
cooldown/expiry boundaries, rolling hourly throttling,
cross-account throttle concurrency, attempt exhaustion, concurrent first
issuance/resends, concurrent wrong/correct verification, mixed
resend/verification, and role additivity/idempotency.

Test-only PostgreSQL triggers force STUDENT insertion and challenge deletion to
fail through the real persistence path. Each test checks that the account remains
pending, its verification timestamp and STUDENT are absent, the original
challenge is unchanged, and verification succeeds after removing the trigger.
No production test hooks are used. Each integration case also checks unrelated
security tables remain empty and the Redis template receives no interactions.

`EmailVerificationOutboxDispatcherIntegrationTest` uses PostgreSQL 17 to cover
claim exclusivity, committed claim transactions, SMTP outside a transaction,
retrying the same OTP, maximum attempts, expiry, missing keys, ciphertext
tampering, stale leases, crash recovery, and terminal payload clearing.
Dispatcher, configuration, template, and Spring Mail unit tests cover sanitized
failure mapping, backoff, redacted diagnostics, and runtime validation.

`RawEmailVerificationOtpTest`, `SecureEmailVerificationOtpGeneratorTest`,
`OutboxPayloadCipherTest`, `EmailVerificationPolicyTest`, and
`EmailVerificationRequestServiceTest` cover format, generation, production
policy, key validation/rotation, nonce size/freshness, AAD/ciphertext tampering,
redacted diagnostics, and durable-acceptance result mapping. ArchUnit protects
application boundaries from JPA entity dependencies, allowing the existing
transaction implementation convention.
