# Gateway

Independent Spring Boot 4.1.1 / Java 21 project for the Quizopia 2.0 platform scaffold.

Run independently from this directory:

- Windows: `mvnw.cmd test` or `mvnw.cmd verify`
- Unix-like shells: `./mvnw test` or `./mvnw verify`

The Gateway is the browser-facing HTTP edge. It routes `/api/auth/**` to Identity,
validates Quizopia bearer JWTs for protected requests, and permits anonymous access
only to the exact `POST` registration, email-verification, login, refresh, and
logout endpoints. `GET /api/auth/me` requires a Quizopia `TOKEN_USER` principal.
`POST /api/auth/teacher-enablement` is routed by the existing `/api/auth/**`
route and also requires `TOKEN_USER`; SERVICE tokens are rejected at the edge.

Browser CORS uses the explicit origins supplied by `GATEWAY_ALLOWED_ORIGINS` and
allows credentials. The local default is `http://localhost:3000`; production has
no default and fails startup for missing, empty, wildcard, malformed, or duplicate
origins. Gateway forwards `Cookie`, `Set-Cookie`, `Origin`, and `Authorization`
without implementing Identity session or credential behavior.
Gateway validates the issuer supplied by `IDENTITY_ISSUER` while obtaining
signing keys from `IDENTITY_JWKS_URI`; local development uses
`http://localhost:8081` for both Identity authority and JWKS host.

Identity remains the owner of the auth HTTP contract and its service-local
OpenAPI document. Gateway does not duplicate or aggregate those schemas.

Default local port: 8080. See `.env.example` and the root development documentation for configuration.

Spring Boot and Maven do not load `.env` automatically. For Windows local
development, copy the example and load it into the current PowerShell process
before starting Gateway from that same shell:

```powershell
Copy-Item .env.example .env
foreach ($rawLine in Get-Content -LiteralPath .env) {
    $line = $rawLine.Trim()
    if ($line.Length -eq 0 -or $line.StartsWith('#')) { continue }
    $separator = $line.IndexOf('=')
    if ($separator -le 0) { throw "Invalid .env entry" }
    $name = $line.Substring(0, $separator).Trim()
    if ($name -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') { throw "Invalid .env variable name" }
    $value = $line.Substring($separator + 1)
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}
.\mvnw.cmd spring-boot:run
```

The local Gateway values must match the Identity authority:
`IDENTITY_ISSUER=http://localhost:8081` and
`IDENTITY_JWKS_URI=http://localhost:8081/oauth2/jwks`. The Identity README
contains the key-generation, service startup, and register-to-`/me` smoke flow.
