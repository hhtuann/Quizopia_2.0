# API contracts

This directory is reserved for versioned OpenAPI artifacts generated from
implemented Spring APIs. It intentionally contains no speculative DTOs or
generated clients yet.

Intended workflow:

1. Run the owning service with its OpenAPI endpoint enabled.
2. Download `/v3/api-docs` for that service.
3. Store reviewed contract artifacts here by service and version.
4. Generate frontend TypeScript clients from the reviewed contract using the
   repository-approved generator once the first business API exists.

The gateway remains the browser edge; contracts describe service APIs and
their public gateway mapping must be reviewed together.
