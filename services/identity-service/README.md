# Identity Service

Independent Spring Boot 4.1.1 / Java 21 project for the Quizopia 2.0 platform scaffold.

Run independently from this directory:

- Windows: `mvnw.cmd test` or `mvnw.cmd verify`
- Unix-like shells: `./mvnw test` or `./mvnw verify`

The service includes the in-progress Identity authentication core and technical
health/metrics and OpenAPI configuration. The internal email-verification flow
generates six-digit OTPs with `SecureRandom`, persists only encoded OTP material,
enforces the accepted production challenge policy and PostgreSQL-backed rolling
issuance limit, and atomically reuses trusted account activation. See
[the Step 10 implementation notes](../../docs/development/identity-email-verification-core.md).
Verification issuance atomically stores the hashed challenge, rolling issuance
history, and one AES-256-GCM encrypted transactional-outbox job. An
Identity-owned background dispatcher claims due jobs with PostgreSQL leases,
decrypts only in memory, and sends the same committed OTP through Spring Mail.
Delivery is at least once with bounded exponential backoff and terminal payload
clearing. The anonymous `POST /api/auth/register`,
`POST /api/auth/email-verification/request`, and
`POST /api/auth/email-verification/confirm` endpoints use the exact stored
username for verification. They return only the documented status/error
envelope and never return OTP, password, roles, user IDs, or tokens. Anonymous
`POST /api/auth/login` accepts an exact username or exact verified email,
creates one absolute seven-day refresh family, returns the short-lived Quizopia
access JWT, and places the opaque refresh credential only in the host-only
`quizopia_refresh` HttpOnly cookie. `POST /api/auth/refresh` reads only that
cookie, requires an exact Origin from `GATEWAY_ALLOWED_ORIGINS`, rotates the
credential, and preserves the original family expiry. Production supplies the
allowed-origin list at runtime. `POST /api/auth/logout` reuses that Origin gate,
idempotently revokes only the recognized current refresh family, and clears the
cookie without creating access-token blacklist state. `GET /api/auth/me`
requires a USER bearer token and returns only the authoritative current ID,
username, email, and persisted roles after current eligibility and PostgreSQL
revocation-cutoff checks. The outbox
cipher requires `IDENTITY_EMAIL_OUTBOX_ACTIVE_KEY_VERSION`
plus runtime key-ring entries such as
`QUIZOPIA_IDENTITY_EMAIL_OUTBOX_ENCRYPTION_KEYS_V1`; each entry is Base64 for
exactly 32 key bytes. These are variable-name examples only: no production key
material belongs in Git, and there is no plaintext fallback.

The local profile enables the dispatcher against Mailpit at `localhost:1025`
with `no-reply@quizopia.local`. Production supplies `MAIL_HOST`, `MAIL_PORT`,
`MAIL_SMTP_AUTH`, `MAIL_SMTP_STARTTLS_ENABLE`, `MAIL_USERNAME`, `MAIL_PASSWORD`,
`IDENTITY_EMAIL_OUTBOX_DISPATCHER_ENABLED`, and
`IDENTITY_EMAIL_FROM_ADDRESS` at runtime. Poll interval, batch size, one-minute
lease, maximum attempts, and retry delays are operational settings documented in
`.env.example`. No reusable SMTP credential or encryption key is committed.

Identity defines the cross-service access-token principal contract in
[ADR-014](../../docs/decisions/ADR-014-access-token-principal-and-authority-contract.md).
The production user-token issuer signs short-lived RS256 JWTs only for active,
email-verified users with the baseline `STUDENT` role. Existing Client
Credentials tokens retain OAuth scopes and now carry the explicit `SERVICE`
principal discriminator. Service principals cannot access the user-only
current-profile endpoint.

Default local port: 8081. See `.env.example` and the root development documentation for configuration.

## Local signing-key bootstrap

The complete local browser-auth flow requires Identity token issuance and a local
RSA signing pair. Keep the pair under the ignored `.local-secrets/` directory;
never commit either file. From `services/identity-service`, generate a PKCS#8
private key and X.509 SubjectPublicKeyInfo public key:

```powershell
New-Item -ItemType Directory -Force .local-secrets | Out-Null
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out .local-secrets/identity-private.pem
openssl pkey -in .local-secrets/identity-private.pem -pubout -out .local-secrets/identity-public.pem
```

Copy `.env.example` to an ignored `.env` and supply its outbox encryption key.
Generate a separate 32-byte local key and paste only its Base64 output into
`QUIZOPIA_IDENTITY_EMAIL_OUTBOX_ENCRYPTION_KEYS_V1` in `.env`:

```powershell
$localOutboxKey = New-Object byte[] 32
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $random.GetBytes($localOutboxKey) } finally { $random.Dispose() }
[Convert]::ToBase64String($localOutboxKey)
```

This value is a local secret and must remain in the ignored `.env` file.
The local auth variables are:

- `IDENTITY_AUTHORIZATION_SERVER_ENABLED=true`
- `IDENTITY_ISSUER=http://localhost:8081`
- `IDENTITY_SIGNING_KEY_KID=quizopia-local-dev`
- `IDENTITY_SIGNING_KEY_PRIVATE_KEY_PATH=.local-secrets/identity-private.pem`
- `IDENTITY_SIGNING_KEY_PUBLIC_KEY_PATH=.local-secrets/identity-public.pem`
- `IDENTITY_JWKS_URI=http://localhost:8081/oauth2/jwks`

Start PostgreSQL, Redis, and Mailpit, then Identity, then Gateway. Gateway must
receive the same `IDENTITY_ISSUER` and the Identity JWKS URL. Register, confirm
the delivered Mailpit OTP, log in through Gateway, and call
`GET /api/auth/me` with the returned access token. The private key is read only
by Identity; Gateway and other resource servers use JWKS.

Spring Boot and Maven do not import `.env` automatically. From the Identity
directory, load the file into the current PowerShell process without evaluating
its contents, then start Maven from that same shell:

```powershell
Copy-Item .env.example .env
# Edit .env and supply a separate local outbox encryption key before continuing.
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

Open a second PowerShell window in `gateway`, copy its `.env.example`, use the
same safe loader, and then run `.\mvnw.cmd spring-boot:run`. The two files use a
coherent local authority: `IDENTITY_ISSUER=http://localhost:8081` and
`IDENTITY_JWKS_URI=http://localhost:8081/oauth2/jwks`.

With the browser edge at `http://localhost:8080`, the following PowerShell flow
exercises the local contract. Read the six-digit OTP from Mailpit at
`http://localhost:8025` when prompted:

```powershell
$api = 'http://localhost:8080'
$username = 'local-student'
$email = 'local-student@gmail.com'
$password = Read-Host 'Local test password'
$json = @{ username = $username; email = $email; password = $password } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$api/api/auth/register" -ContentType 'application/json' -Body $json
Invoke-RestMethod -Method Post -Uri "$api/api/auth/email-verification/request" -ContentType 'application/json' -Body (@{ username = $username } | ConvertTo-Json)
$otp = Read-Host 'OTP from Mailpit'
Invoke-WebRequest -Method Post -Uri "$api/api/auth/email-verification/confirm" -ContentType 'application/json' -Body (@{ username = $username; otp = $otp } | ConvertTo-Json)
$login = Invoke-WebRequest -Method Post -Uri "$api/api/auth/login" -ContentType 'application/json' -Body (@{ identifier = $username; password = $password } | ConvertTo-Json) -SessionVariable browser
$accessToken = ($login.Content | ConvertFrom-Json).accessToken
Invoke-RestMethod -Method Get -Uri "$api/api/auth/me" -Headers @{ Authorization = "Bearer $accessToken" }
```
