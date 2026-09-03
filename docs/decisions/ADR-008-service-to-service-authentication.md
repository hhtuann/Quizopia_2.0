# ADR-008: OAuth2 Client Credentials for Service-to-Service Authentication

Status: **Accepted**

## Decision

Synchronous internal service calls use direct internal REST and OAuth2 Client Credentials service identities issued by Identity/Authorization Server.

Service access tokens are short-lived JWTs with least-privilege scopes.

Services cache a valid service token until near expiry rather than requesting one per call.

Do not route internal service calls through the public Gateway.

Do not treat "internal network" as sufficient authentication.

mTLS is not required in the baseline; it may be layered on later if deployment requirements justify it.
