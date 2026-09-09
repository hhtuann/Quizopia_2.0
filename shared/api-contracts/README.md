# API contracts

`shared/api-contracts` is the technical coordination boundary for reviewed
OpenAPI artifacts. Each contract is owned by the service that implements the
API; the Gateway does not own domain API contracts.

## Lifecycle and publication

The service implementation is the source from which its contract is produced:

```text
implemented + accepted Spring API
        ↓
OpenAPI exposed by owning service
        ↓
reviewed contract artifact
        ↓
shared/api-contracts
        ↓
frontend generated/derived contract/client
```

Publish an artifact here only when the API is implemented, its semantics are
accepted, and its owning service is clear. Do not hand-author speculative APIs
or DTO artifacts to drive a future service implementation unless a later
accepted repository decision explicitly changes this workflow.

Store reviewed artifacts by owning service and artifact version. Artifact or
schema versioning records contract revisions; it does not decide the runtime
public API versioning strategy. Paths, headers, URL versioning, and runtime API
semantic-version policies remain open under `API-03`.

## Gateway and consumers

Browser business HTTP traffic goes through Spring Cloud Gateway. When a service
API is exposed through the Gateway, review its contract together with the
corresponding Gateway route or mapping while retaining service ownership of the
contract.

Generate or derive frontend TypeScript types and clients from the reviewed
OpenAPI contract when practical. Do not maintain a second handwritten DTO model
when a generated contract exists. When an owning service changes an API,
regenerate and review its artifact, then inspect and regenerate affected
consumers as appropriate.

Select a generator only when an implemented business API exists and the
repository has accepted the approach. This directory does not currently choose
or configure one.

## Shared boundary

This directory contains technical contract artifacts only. It must not contain
JPA entities, repositories, domain models, business services, shared persistence
abstractions, a shared business-DTO Java library, or a common-domain JAR.
Backend services must not be coupled through a shared business Maven artifact.
