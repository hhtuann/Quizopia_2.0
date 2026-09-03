# Authentication and Identity Architecture

Status: **Accepted pre-scaffold topology v0.2**

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

The exact configured access-token TTL may remain deployment/security configuration as long as it stays short-lived.

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
  "type": "SERVICE",
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
