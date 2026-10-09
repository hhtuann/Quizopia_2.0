# Authentication and Identity Architecture

Status: **Accepted topology and access-token contract v0.3**

## Identity role

`identity-service` owns authentication and acts as the Quizopia Authorization Server using Spring Authorization Server.

External Google identity is used to authenticate/link a user, but Quizopia services consume **Quizopia-issued** tokens rather than Google access tokens.

## User login methods

One internal Quizopia user may authenticate using:

1. local username/password;
2. Google OIDC identity.

Provider credentials/identity are separate from the internal user profile.

## Local registration

Accepted Wave 2A baseline:

1. username/password + exact stored `@gmail.com` address;
2. create pending-email-verification account/state;
3. send OTP;
4. verify OTP;
5. activate account;
6. grant `STUDENT`.

OTP requirements:

- exactly six ASCII decimal digits generated with cryptographically secure randomness;
- hashed at rest;
- 60-second expiry (proposed FE-09 contract change; requires product/security leader approval before merge);
- 60-second resend cooldown;
- five failed attempts per OTP;
- a successful issuance replaces the previous challenge;
- at most five successful issuances per exact stored email in the rolling window
  `(now - 1 hour, now]`.

Registration preserves current exact stored-value and case semantics. It does not
trim or lowercase the address, remove plus aliases, or apply Gmail dot
normalization. Duplicate pending accounts may share the exact email; the
PostgreSQL verified-email ownership constraint permits only one verified owner.

Identity owns a PostgreSQL transactional email outbox for verification delivery.
A successful issuance transaction atomically persists the hashed challenge, the
rolling issuance-history record, and exactly one durable outbox job. SMTP and
other network delivery happen only after that transaction commits. Delivery
retries reuse the OTP from the committed job rather than generating a new OTP.

The Wave 2A public verification subject is the exact stored username because
multiple pending accounts may share an exact email. Identity exposes only these
anonymous `POST` operations:

- `/api/auth/register` accepts `username`, `email`, and `password`, and returns
  `202 {"status":"VERIFICATION_REQUIRED"}` after creating a pending account;
- `/api/auth/email-verification/request` accepts `username` and always returns
  `202 {"status":"VERIFICATION_REQUEST_ACCEPTED"}` for both durable issuance
  and enumeration-sensitive no-op outcomes;
- `/api/auth/email-verification/confirm` accepts `username` and six-digit `otp`,
  returns `204` after activation, and maps every expected verification failure to
  public `400 AUTH_VERIFICATION_FAILED`.

Registration validation failures use the standard invalid-request envelope.
Username conflicts use `409 AUTH_USERNAME_UNAVAILABLE`; other registration
conflicts use `409 AUTH_REGISTRATION_FAILED`. No registration or verification
response includes an OTP, password, role, user ID, or session/token material.

Local browser login accepts an exact username or exact verified email at
`POST /api/auth/login`. Identity reuses the shared identifier resolution and
returns generic `401 AUTH_INVALID_CREDENTIALS` for every credential or account
eligibility rejection. A successful login atomically creates one refresh family
and initial hashed refresh credential and issues one Quizopia user access JWT.
JWT failure rolls back the refresh persistence.

The login response contains only `accessToken`, token type `Bearer`, and the
remaining access-token lifetime. The opaque refresh credential is returned only
as the `quizopia_refresh` HttpOnly cookie with host-only domain semantics,
`Path=/api/auth`, and `SameSite=Lax`. It is Secure in production and non-Secure
only for local/development operation. Its persistent lifetime is derived from
the committed refresh-family expiry rather than an independent controller TTL.

The outbox persists only an AES-256-GCM encrypted form of the six ASCII OTP
digits. Encryption uses a 256-bit key, a fresh cryptographically random 96-bit
nonce for every payload, and a 128-bit authentication tag. Each job stores the
ciphertext, nonce, non-secret key version, and an independent payload-format
version. It never stores the plaintext OTP or a rendered subject/body. Terminal
`SENT`, `FAILED`, and `EXPIRED` transitions must clear ciphertext and nonce.

Authenticated additional data binds the ciphertext to its job ID, exact
recipient email, template type, OTP expiry, key version, and payload-format
version. The canonical encoding is version 1 and uses this fixed big-endian
binary sequence: the ASCII magic bytes `QZEV-AAD`, one byte containing AAD
encoding version `1`, the UUID most/least-significant 64-bit values, then each
UTF-8 string as a 32-bit byte length followed by its bytes (recipient, template
type), the expiry as a 64-bit epoch-second plus 32-bit nanosecond value, the key
version as another length-prefixed UTF-8 string, and finally the 32-bit payload
format version. No delimiter-based encoding is used.

Before encryption and persistence, Identity truncates OTP expiry to PostgreSQL's
microsecond timestamp precision. The AAD still encodes the canonical expiry's
nanosecond field, which is therefore always a multiple of 1,000. This guarantees
that a dispatcher rebuilding AAD from a committed row uses the exact value used
during encryption.

New jobs begin in `PENDING`. Identity's background dispatcher atomically claims
due work by moving it to `CLAIMED` with a unique worker identity and expiring
lease. The short PostgreSQL claim transaction commits before any decryption,
template rendering, or SMTP network I/O. Multiple instances use row locking with
`SKIP LOCKED`; an expired lease makes abandoned work reclaimable.

Before external SMTP, the dispatcher durably increments the job's attempt count
and revalidates that the job is still the challenge's current issuance. A live
delivery heartbeat extends only the current owner's lease, preventing a second
worker from reclaiming a job during slow SMTP. Challenge replacement,
verification success, and verified ownership by a duplicate pending account
make older work non-deliverable and clear its encrypted payload.

Delivery is at least once. A crash after SMTP acceptance but before `SENT` is
committed can cause the same OTP to be delivered again after lease expiry. It
never creates a new challenge, issuance-history row, or outbox job. Retryable
failures use bounded exponential backoff and preserve the encrypted payload. The
durable attempt count is reserved before external I/O, so ambiguous crash-after-
send recovery still consumes the bounded budget. The operational default is five attempts.
Retries stop at OTP expiry. Successful delivery becomes `SENT`; permanently
failed, corrupt, or expired work becomes `FAILED` or `EXPIRED`. Every terminal
transition records its terminal time and clears ciphertext and nonce.

Identity owns provider-neutral payload-cipher and key-ring abstractions. Runtime
configuration supplies an active key version and a mapping of versions to
exactly 32 decoded key bytes. New jobs use the active version; historical keys
remain independently configurable for later delivery. Missing or malformed
active-key configuration fails startup, and encryption failure rolls back the
complete issuance transaction. Production key material is supplied through
runtime secret injection and is never committed or persisted. Local and test
environments use the same cipher implementation with separately supplied
development/test keys and no plaintext fallback.

The dispatcher decrypts only in memory immediately before rendering and sending
through Identity's provider-neutral Spring Mail adapter. Local development uses
that same adapter with Mailpit; production supplies SMTP host, port,
authentication, TLS, credentials, and sender address at runtime. OTPs, rendered
messages, ciphertext, nonces, provider responses, credentials, and key material
must not be logged. V10 associates each active outbox job with its account so
the dispatcher can render the exact username without resolving an ambiguous
pending email address. V10 refuses to migrate while any legacy `PENDING` or
`CLAIMED` job lacks deterministic account context; the accepted job and its
encrypted payload remain unchanged until operators drain or resolve it.
Terminal legacy jobs may retain a null account link.

## Google login/account linking

Google login resolves a stable provider subject and verified email information.

If an existing verified local Quizopia account safely matches the same verified Gmail identity, link the Google provider identity to that internal user rather than creating a duplicate.

Ambiguous/conflicting cases fail safely; exact UX remains TBD.

## User access token

Accepted model:

- JWT;
- signed with RS256;
- short-lived;
- issuer is the runtime `IDENTITY_ISSUER` value owned by Identity;
- frontend keeps access token in memory, not localStorage;
- Gateway validates the JWT;
- every protected microservice validates the JWT independently using Quizopia JWKS/public key material.

Gateway and Identity validate both the trusted RS256 signature/JWKS material and
the expected `iss` value. Explicit JWKS configuration avoids an OIDC discovery
startup dependency while retaining standard timestamp and issuer validation.

The configured access-token TTL remains deployment/security configuration and must stay short-lived. User and service TTLs may be configured separately; when the user TTL is omitted, it uses the configured service-token TTL.

### Access-token principal contract

All Quizopia access tokens contain the case-sensitive `principal_type` claim with
exactly one of these values:

- `USER`;
- `SERVICE`.

Consumers must validate this discriminator. They must not infer principal type
from whether `sub` parses as a UUID.

A user access token has this contract:

- `sub`: internal Quizopia user ID encoded as a canonical UUID string;
- `principal_type`: string `USER`;
- `roles`: JSON array of unprefixed, case-sensitive Identity global role names;
- `roles` values are limited to `STUDENT`, `TEACHER`, and `ADMIN`;
- `scope` is absent;
- `iss`, `iat`, and `exp` use their standard JWT meanings.

Example claims:

    {
      "iss": "https://identity.quizopia.example",
      "sub": "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
      "iat": 1789257600,
      "exp": 1789257900,
      "principal_type": "USER",
      "roles": ["STUDENT", "TEACHER"]
    }

User tokens contain no email or profile fields. Identity profile data remains
authoritative in Identity Service.

A Client Credentials service access token has this contract:

- `sub`: OAuth client ID/service identity, such as `assessment-service`;
- `principal_type`: string `SERVICE`;
- `scope`: mandatory, non-empty JSON array of OAuth service-scope strings;
- every `scope` element is a non-empty, non-blank string;
- `roles` is absent;
- `iss`, `iat`, and `exp` use their standard JWT meanings.

Example claims:

    {
      "iss": "https://identity.quizopia.example",
      "sub": "assessment-service",
      "iat": 1789257600,
      "exp": 1789257645,
      "principal_type": "SERVICE",
      "scope": ["classroom.membership.read"]
    }

Consuming Spring services map claims as follows:

- `principal_type=USER` adds authority `TOKEN_USER`;
- each user role adds `ROLE_<role>`, for example `TEACHER` becomes
  `ROLE_TEACHER`;
- `principal_type=SERVICE` adds authority `TOKEN_SERVICE`;
- each accepted service scope adds exactly one `SCOPE_<scope-value>` authority;
- user roles and service scopes are not combined or treated as equivalent;
- missing, unknown, malformed, or mixed principal claims are rejected.

A service token is malformed when `scope` is missing or empty, is any JSON type
other than an array, or contains a null, non-string, empty, or blank element.
Consumers reject the complete token before granting `TOKEN_SERVICE` or any
`SCOPE_*` authority. A service token containing `roles` is also rejected.

A user-only endpoint requires `TOKEN_USER`. A teacher-only user endpoint
requires both `TOKEN_USER` and `ROLE_TEACHER`. This rejects a valid service
token even when Identity issued and signed it. Service endpoints require
`TOKEN_SERVICE` plus their least-privilege `SCOPE_*` authority.

## Refresh token

Accepted model:

- opaque cryptographically random token;
- HttpOnly cookie;
- Secure in production;
- server stores only a hash;
- rotate on refresh;
- keep refresh-family lineage;
- detect token reuse;
- reuse revokes the relevant active family/session according to the final implementation policy.

Refresh/session state belongs to Identity Service.

The initial browser refresh family has an absolute lifetime of seven days from
successful login. Rotation retains the original family and expiry and therefore
cannot create a sliding session or extend replacement credentials beyond the
original family lifetime.

Browser refresh is exposed at `POST /api/auth/refresh`. It accepts the opaque
credential only from the exact `quizopia_refresh` cookie, rotates it once, and
returns a new user access JWT plus a replacement cookie whose persistent lifetime
is the remaining time before the original family expiry. Every invalid session
condition maps to `401 AUTH_REFRESH_FAILED`; consumed-token reuse still commits
revocation of the whole family.

Refresh and current-session logout require one exact browser `Origin` value
before any session mutation.
Identity reuses the explicit `GATEWAY_ALLOWED_ORIGINS` configuration contract
already used by the public edge. Production must supply that value at runtime;
the existing local default is `http://localhost:3000`. Missing, null, duplicate,
wildcard, or non-exact origins receive the generic `403 ACCESS_DENIED` envelope.

Current-session logout is exposed at `POST /api/auth/logout`. It resolves only
the exact `quizopia_refresh` cookie and idempotently revokes the recognized
current family. A consumed credential still triggers family revocation as a
reuse security side effect. Missing, malformed, unknown, expired, or already
revoked credentials receive the same `204 No Content` response, and every
successful Origin-approved response clears the host-only cookie at `/api/auth`. Origin
rejection occurs before mutation or cookie clearing. Logout creates no successor
credential and does not create access-token persistence or a JTI blacklist.
Already-issued short-lived access JWTs therefore remain usable until expiry
unless the account or authoritative user revocation cutoff independently makes
them ineligible.

The user-only `GET /api/auth/me` endpoint accepts a valid `TOKEN_USER` access
JWT, then reloads the account and roles from Identity persistence. It requires
the same established USER eligibility as login and refresh: the account exists,
is active, has a verified email, and currently has `STUDENT`. Current roles are
returned in the existing deterministic lexical order rather than copied from
the JWT. Identity also compares the trusted JWT `iat` with the authoritative
PostgreSQL revocation cutoff using the established inclusive rule: a token with
`iat <= revoked_before` is rejected. Service principals cannot use `/me`.

The user-only `POST /api/auth/teacher-enablement` endpoint accepts no request
body and derives the target exclusively from the authenticated USER subject.
Identity locks and reloads the authoritative account, requires the account to be
active, email-verified, and still assigned the authoritative persisted `STUDENT`
role, then atomically adds `TEACHER` without removing `STUDENT` or other valid
roles. Missing `STUDENT` fails closed as the same generic ineligible result. The
first real grant and its minimal self-service audit record commit in one
transaction. Repeated requests are successful no-ops and do not add another role
or audit row. SERVICE principals and ineligible users receive the standard
forbidden result.

Teacher enablement does not mutate an already-issued access JWT, revoke or
replace its refresh family, or extend the family expiry. The next normal refresh
rotation reloads the authoritative current role set during access-token issuance,
so the new JWT contains `STUDENT` and `TEACHER`; `/api/auth/me` independently
continues to reload current roles from Identity persistence.

The browser reaches these endpoints through Spring Cloud Gateway. Gateway grants
anonymous access only to the exact `POST` registration, email-verification,
login, refresh, and logout paths; wrong methods and neighboring `/api/auth/**`
paths remain protected. `GET /api/auth/me` requires a validated `TOKEN_USER`
principal at Gateway. Teacher enablement uses the existing authenticated
`/api/auth/**` Gateway route; Identity independently requires `TOKEN_USER` and
enforces the authoritative account lifecycle before mutation.

Gateway credentialed CORS uses the same explicit `GATEWAY_ALLOWED_ORIGINS`
source. Valid preflights are handled at the edge. Routed requests preserve the
browser `Origin`, incoming refresh `Cookie`, and bearer `Authorization` headers,
and Identity `Set-Cookie` responses pass through without attribute rewriting.
Identity remains authoritative for passwords, OTPs, refresh sessions, trusted
Origin checks, and public auth errors. Identity's OpenAPI remains service-local;
Gateway does not maintain a second auth schema or API aggregator.

## Immediate account disable/revocation

Quizopia does not rely only on waiting for access JWT expiry.

When an account is disabled/revoked:

1. Identity commits authoritative account/session revocation state;
2. Identity propagates revocation state/event;
3. Redis provides near-immediate revocation lookup/distribution for Gateway/services;
4. refresh is rejected;
5. Gateway and protected services reject revoked users even if an otherwise-valid short-lived JWT has not expired.

Exact cache-failure/fail-open-vs-fail-closed behavior remains an operational/security open question.

## Service-to-service authentication

Internal synchronous calls use OAuth2 Client Credentials.

Each service has a service identity/client with least-privilege scopes.

Example conceptual service token:

```json
{
  "sub": "assessment-service",
  "principal_type": "SERVICE",
  "scope": ["classroom.membership.read"]
}
```

Services cache short-lived service access tokens until near expiry rather than requesting a token for every call.

Internal network location alone is not sufficient authentication.

mTLS is not a baseline requirement; it may be considered later for stricter production deployments.

## Authorization

Frontend route/workspace guards are UX only.

Every backend service enforces resource/role/ownership/state authorization for data it owns.

User roles:

- `STUDENT`
- `TEACHER`
- `ADMIN`

A user can have `STUDENT + TEACHER` simultaneously.

Learning/Teaching workspace is UI context, not a token/account mutation.
