# Identity email verification core (Wave 1A Step 10)

The internal Identity application boundary is `EmailVerificationService`:

```java
issueChallenge(UUID userId, RawEmailVerificationOtp rawOtp, EmailVerificationPolicy policy)
verify(UUID userId, RawEmailVerificationOtp rawOtp)
```

The caller resolves the internal user and supplies transient OTP material. This
step has no HTTP endpoint, generator, email delivery, login, or token issuance.
The results are technical enums and contain neither raw OTP material nor hashes.

`RawEmailVerificationOtp` accepts non-null, nonblank material without format,
alphabet, length, or normalization rules. Its diagnostic representation is
redacted. Only the existing Identity `serviceClientPasswordEncoder` receives the
raw value for encoding/matching; persistence receives the encoded hash. The
existing encoder also serves local registration. This step does not change it.

`EmailVerificationPolicy` requires an explicit positive expiry `Duration`, a
positive failed-attempt limit, and a nonnegative resend cooldown `Duration`.
Zero cooldown technically permits immediate replacement. There are no production
policy constants, configuration values, or fallback values. Examples in tests
are fixtures only. ID-01 and ID-02 in [open questions](../open-questions.md) remain
unresolved. OTP format/generation and broader throttling subjects/dimensions and
values remain deferred, as do email-send/retry rules.

## Persistence and lifecycle

V6 adds only `email_verification_challenge`. Its primary key and foreign key are
`user_id`, referencing Identity's `user_account`. It stores `otp_hash`,
`issued_at`, `expires_at`, `resend_not_before`, `failed_attempts`, and
`max_attempts`. Database checks enforce nonblank encoded material, valid attempt
bounds, positive lifetime, and a cooldown boundary at or after issuance. There is
no duplicate email/username, delivery state, audit history, or throttle metadata.
V1–V5 are unchanged.

An issuance creates or replaces the single current challenge. It derives both
time boundaries from the injected server `Clock` and explicit policy, and resets
failed attempts to zero. The production Clock is `Clock.systemUTC()`. No caller
can pass a current-time argument. Decisions read time after acquiring the locks.

If `now < resend_not_before`, issuance returns `COOLDOWN` and leaves every field
unchanged. At or after that boundary, a replacement uses the newly supplied OTP
and policy. Only the replacement hash is retained. Expired/exhausted challenges
can likewise be replaced once their cooldown permits it.

Issuance returns `ISSUED`, `COOLDOWN`, `ALREADY_VERIFIED`, `NOT_FOUND`, or
`CONFLICT`.

Verification first checks authoritative account state, then challenge presence,
expiry, attempt exhaustion, and finally `PasswordEncoder.matches`. Its outcomes
are `VERIFIED`, `INVALID_OTP`, `EXPIRED`, `ATTEMPTS_EXHAUSTED`,
`NO_ACTIVE_CHALLENGE`, `ALREADY_VERIFIED`, `NOT_FOUND`, and `CONFLICT`.

At `now >= expires_at`, verification returns `EXPIRED` without modifying the
challenge. Expiry takes precedence if a challenge is also exhausted. A mismatch
increments and persists the failed-attempt counter. The mismatch reaching the
limit returns `ATTEMPTS_EXHAUSTED`; subsequent attempts cannot match or increment
past the limit. Failed attempts do not change time boundaries.

## Transactions and concurrency

Both transaction methods lock the user row first, then any existing challenge
row, using PostgreSQL pessimistic write locks. The user lock serializes first
issuance even when a challenge does not yet exist. The primary key additionally
enforces a single current challenge. No JVM synchronization is used.

For a correct, eligible OTP, `EmailVerificationTransaction` calls the existing
Step 9 `TrustedEmailActivationService`, which calls the separate Spring-proxied
`TrustedEmailActivationTransaction`. Its existing `REQUIRED` propagation joins
the OTP transaction. It reacquires the already-held user lock and performs the
approved activation and additive STUDENT persistence. Step 10 contains no copy of
that transition/role logic. It deletes and flushes the current challenge only
after activation succeeds. Any persistence exception rolls back the complete
transaction.

Repeated verification reads the active, verified account and returns
`ALREADY_VERIFIED`, preserving the original timestamp. An active account without
a verification timestamp, pending account with one, or unsupported lifecycle
state returns `CONFLICT` without repair. Unknown users are never created. An
already-verified account with an existing challenge is left untouched.

There are no provider identity, refresh session/token, revocation, service-client,
Redis, event, or delivery side effects. Successful verification changes only the
approved account activation fields/role and removes its challenge.

## Validation

`EmailVerificationIntegrationTest` uses PostgreSQL 17 Testcontainers with Flyway
and Hibernate validation, explicit policy fixtures, and a controlled Clock. It
covers hash-only storage, account-state handling, cooldown/expiry boundaries,
attempt exhaustion, concurrent first issuance/resends, concurrent wrong/correct
verification, mixed resend/verification, and role additivity/idempotency.

Test-only PostgreSQL triggers force STUDENT insertion and challenge deletion to
fail through the real persistence path. Each test checks that the account remains
pending, its verification timestamp and STUDENT are absent, the original
challenge is unchanged, and verification succeeds after removing the trigger.
No production test hooks are used. Each integration case also checks unrelated
security tables remain empty and the Redis template receives no interactions.

`RawEmailVerificationOtpTest` and `EmailVerificationPolicyTest` cover the technical
input invariants. ArchUnit protects application boundaries from JPA entity
dependencies, allowing the existing transaction implementation convention.
