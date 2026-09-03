# Authentication and Identity Architecture

Status: **Baseline v0.1**

## Login methods

Quizopia supports two ways to authenticate into one internal Quizopia account:

1. local username/password;
2. Google authentication.

These are identities/credentials connected to the same internal user.

## Local registration

Baseline flow:

1. user enters:
   - username;
   - password;
   - Gmail address;
   - required profile fields;
2. create account in pending-email-verification state;
3. send OTP to Gmail;
4. user submits OTP;
5. Gmail is verified;
6. account becomes active;
7. grant `STUDENT`.

OTP requirements:

- short expiry;
- resend cooldown;
- attempt limit;
- store a hash rather than plaintext OTP;
- do not activate the account until Gmail verification succeeds.

Exact OTP duration/limits are **TBD**.

## Gmail requirement

Current product wording requires a Gmail address for local registration.

Whether Google Workspace/custom-domain Google accounts are accepted is **TBD**.

Agents must not silently broaden or narrow this requirement.

## Google login

Google login links to the internal Quizopia user.

Store provider identity separately from user profile.

Conceptual model:

```text
User
  -> LOCAL credential
  -> GOOGLE provider identity
```

Google provider identity must use the provider's stable subject identifier rather than treating mutable display data as the provider primary key.

## Account linking

Required behavior:

- if an already verified local Quizopia account owns the same verified Gmail returned by Google, Google identity should link to that internal account rather than create a duplicate;
- ambiguous/conflicting cases must fail safely and require an explicit resolution flow.

Exact conflict UI is **TBD**.

## Roles

- default: `STUDENT`;
- optional additional: `TEACHER`;
- privileged: `ADMIN`.

Role switching in the UI is workspace/persona switching, not identity mutation.

## Tokens / microservice verification

The exact access-token signing/topology choice is **TBD**.

Requirements:

- services must verify caller identity without trusting frontend-only state;
- account disable/revocation behavior must be defined;
- refresh/session credentials remain owned by Identity Service;
- sensitive auth secrets are never shared with unrelated services unless architecture explicitly requires it.

A future ADR should finalize symmetric vs asymmetric signing/JWKS and gateway responsibilities.
