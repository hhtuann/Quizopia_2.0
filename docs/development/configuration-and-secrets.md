# Configuration and Secrets

Status: **Accepted v0.2**

## Principles

- Git contains no real production secret.
- Services receive only configuration/secrets they need.
- Non-secret defaults may be committed.
- environment/deployment configuration supplies runtime values.

## Files

Committed:

- `application.yml` / equivalent non-secret defaults;
- root `.env.example` for local catalog/Compose;
- service-specific `.env.example` or equivalent documented variables.

Not committed:

- real `.env`;
- private keys;
- passwords;
- client secrets;
- production credentials.

## Service ownership

Examples:

Assessment may receive:

- its DB credentials;
- RabbitMQ credentials;
- internal service client credentials relevant to its calls.

Assessment should not receive Google client secrets or another service DB password.

AI Service should not receive Identity database credentials.

## Production secret manager

Vendor is intentionally TBD.

Possible deployment mechanisms include platform/Kubernetes/Vault/cloud secret stores.

The invariant is runtime injection without storing production secret material in Git.

## Signing keys

Identity Service owns authorization-server private signing material.

Other services receive/publicly resolve only what is required to validate JWTs (for example JWKS/public keys), not the private signing key.

## Local development

Local secret values may live in untracked `.env` files.

Provide safe development examples and fail fast for required security configuration.
