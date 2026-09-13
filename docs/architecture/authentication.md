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

Baseline:

1. username/password + required profile fields + Gmail;
2. create pending-email-verification account/state;
3. send OTP;
4. verify OTP;
5. activate account;
6. grant `STUDENT`.

OTP requirements remain:

- hashed at rest;
- short expiry;
- resend cooldown;
- attempt limit.

Exact values remain TBD.

Whether registration accepts strictly `@gmail.com` or broader Google Workspace addresses remains TBD.

## Google login/account linking

Google login resolves a stable provider subject and verified email information.

If an existing verified local Quizopia account safely matches the same verified Gmail identity, link the Google provider identity to that internal user rather than creating a duplicate.

Ambiguous/conflicting cases fail safely; exact UX remains TBD.

## User access token

Accepted model:

- JWT;
- signed with RS256;
- short-lived;
- issuer is Quizopia/Identity Service;
- frontend keeps access token in memory, not localStorage;
- Gateway validates the JWT;
- every protected microservice validates the JWT independently using Quizopia JWKS/public key material.

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
