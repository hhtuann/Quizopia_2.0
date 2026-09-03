# ADR-002: Local + Google Authentication Linked to One Internal User

Status: **Accepted at product level; token topology TBD**

## Context

Quizopia needs both traditional credentials and Google login.

A user must not receive duplicate Quizopia accounts simply because they use two login methods.

## Decision

One internal Quizopia user may have:

- local username/password credential;
- Google identity.

Local registration requires Gmail OTP verification.

Every activated user receives `STUDENT`.

A user may later add `TEACHER`.

Google login that represents the same already-verified Gmail must resolve/link to the existing internal account rather than create a duplicate, subject to safe conflict handling.

## Consequences

- provider identities are modeled separately from user profile;
- Gmail verification status matters for account linking;
- Identity Service owns credentials, provider identities, OTP, sessions, roles;
- exact JWT signing/JWKS/gateway verification remains TBD.

## Open detail

Whether local registration accepts only `@gmail.com` or also verified Google Workspace/custom-domain Google accounts remains TBD.
