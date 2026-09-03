# Local Development

Status: **Scaffold planning v0.1**

The codebase has not yet been scaffolded, so exact commands are intentionally TBD.

## Expected development services

Initial local environment is expected to include:

- frontend;
- API gateway/edge component (implementation TBD);
- Identity Service;
- Quiz Service;
- Classroom Service;
- Assessment Service;
- Community Service;
- Proctoring Service;
- AI Service;
- PostgreSQL;
- Redis when required by implemented features;
- MinIO/S3-compatible local object storage;
- LiveKit for local proctoring/video work;
- development mail capture for OTP testing;
- event broker after broker decision is finalized.

## Environment principles

- No production secret in Git.
- Provide `.env.example`.
- Fail fast for required security keys.
- Data services should be private in production even if exposed for local development.
- Use health/readiness checks.
- Use deterministic/pinned container versions.

## Local mail

Because local registration uses Gmail OTP in production, development/testing should not send real OTP emails by default.

Use a local/test mail sink or mockable mail adapter.

## AI development

AI provider credentials must be optional for developers not working on AI.

AI service should support a test/fake provider so core platform development is not blocked by external model availability.

## Video development

LiveKit local/self-host mode is expected for development.

Do not require full video recording infrastructure for MVP development.

## To finalize during scaffold

- monorepo directory layout;
- build commands;
- Docker Compose topology;
- port assignments;
- service config conventions;
- shared dev tooling;
- CI commands.
