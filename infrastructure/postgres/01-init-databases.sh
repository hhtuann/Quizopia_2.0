#!/usr/bin/env bash
set -euo pipefail

# LOCAL DEVELOPMENT INFRASTRUCTURE ONLY - never a deployment artifact. This script
# runs once inside the disposable local docker-compose PostgreSQL container. The
# Flyway service migrations NEVER create or password roles (they fail closed when
# these roles are absent). Any real environment must provision the same role
# separation through its own secret-managed process.
#
# Credentials: each password defaults to a public, disposable, local-only value so
# the documented local workflow works out of the box. Override per service through
# <SERVICE>_DB_PASSWORD / <SERVICE>_FLYWAY_DB_PASSWORD environment variables (the
# compose file can pass them through); never commit real secrets.
#
# FB-015 H1 least-privilege provisioning for the two purge services ONLY
# (assessment, proctoring). Identity/Quiz/Classroom/Community/AI keep the legacy
# single-role provisioning: their databases remain owned by the service runtime
# role itself, exactly as before FB-015. The separated services provision:
#   * quizopia_<service>_owner  - NOLOGIN owner of the database and (after the
#     service's V-migrations) of every protected table and SECURITY DEFINER
#     procedure. Never a connection role.
#   * quizopia_<service>_flyway - LOGIN migration role, member of the owner role,
#     used ONLY to run Flyway (spring.flyway.user). Its membership lets migrations
#     transfer object ownership to the owner role; it is never the runtime role.
#   * quizopia_<service>_custodian - NOLOGIN custodian of retention holds and
#     provider-erasure acknowledgments. Membership is granted only through
#     separately approved operator provisioning, never here.
#   * <service>                 - LOGIN application runtime role (DB_USERNAME the
#     service authenticates with). The FB-015 migrations revoke DELETE/TRUNCATE on
#     protected history from it and grant EXECUTE only on the bounded purge
#     procedures, so a compromised runtime principal cannot forge a purge.
create_service_database() {
  local service="$1"
  local separated="$2"
  local database="${service}_db"
  local service_env="$(echo "$service" | tr '[:lower:]' '[:upper:]')"
  local password_var="${service_env}_DB_PASSWORD"
  local flyway_password_var="${service_env}_FLYWAY_DB_PASSWORD"
  local password="${!password_var:-${service}_local_only}"
  local owner="quizopia_${service}_owner"
  local flyway="quizopia_${service}_flyway"
  local custodian="quizopia_${service}_custodian"

  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
    --set=service_user="$service" --set=service_database="$database" \
    --set=service_password="$password" --set=owner_role="$owner" \
    --set=flyway_role="$flyway" --set=custodian_role="$custodian" --set=separated="$separated" \
    --set=flyway_password="${!flyway_password_var:-${service}_flyway_local_only}" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'service_user', :'service_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'service_user')\gexec

SELECT format('CREATE ROLE %I NOLOGIN NOINHERIT', :'owner_role')
WHERE :'separated' = 'true'
  AND NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'owner_role')\gexec

-- Independent preservation/provider approval is never a runtime capability.
-- Operators receive membership only through separately approved provisioning.
SELECT format('CREATE ROLE %I NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE', :'custodian_role')
WHERE :'separated' = 'true'
  AND NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'custodian_role')\gexec

SELECT 1 / CASE WHEN NOT EXISTS (
  SELECT FROM pg_roles WHERE rolname IN (:'owner_role', :'custodian_role')
    AND (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolbypassrls)
) AND NOT EXISTS (
  SELECT FROM pg_auth_members m JOIN pg_roles g ON g.oid=m.roleid
    JOIN pg_roles u ON u.oid=m.member
  WHERE u.rolname=:'service_user' AND g.rolname IN (:'owner_role', :'custodian_role', :'flyway_role')
) THEN 1 ELSE 0 END AS validate_separated_roles
WHERE :'separated' = 'true';

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE', :'flyway_role', :'flyway_password')
WHERE :'separated' = 'true'
  AND NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'flyway_role')\gexec

-- The migration role exercises the owner role's rights (ownership transfer).
SELECT format('GRANT %I TO %I', :'owner_role', :'flyway_role')
WHERE :'separated' = 'true'
  AND NOT EXISTS (SELECT FROM pg_auth_members m JOIN pg_roles g ON g.oid=m.roleid
                   JOIN pg_roles u ON u.oid=m.member
                  WHERE g.rolname = :'owner_role' AND u.rolname = :'flyway_role')\gexec

SELECT format('CREATE DATABASE %I OWNER %I', :'service_database',
              CASE WHEN :'separated' = 'true' THEN :'owner_role' ELSE :'service_user' END)
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = :'service_database')\gexec
SQL

  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
    --set=service_user="$service" --set=service_database="$database" \
    --set=flyway_role="$flyway" --set=separated="$separated" <<'SQL'
REVOKE ALL ON DATABASE :"service_database" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"service_database" TO :"service_user";
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'service_database', :'flyway_role')
WHERE :'separated' = 'true'\gexec
SQL
}

for service in identity quiz classroom community ai; do
  create_service_database "$service" false
done
create_service_database assessment true
create_service_database proctoring true

# pgvector is available for AI's future retrieval work, but no domain tables
# or vector repositories are created by the scaffold.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname ai_db \
  -c 'CREATE EXTENSION IF NOT EXISTS vector;'
