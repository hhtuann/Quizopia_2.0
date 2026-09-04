#!/usr/bin/env bash
set -euo pipefail

create_service_database() {
  local service="$1"
  local database="${service}_db"
  local password="${service}_local_only"

  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
    --set=service_user="$service" --set=service_database="$database" \
    --set=service_password="$password" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'service_user', :'service_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'service_user')\gexec

SELECT format('CREATE DATABASE %I OWNER %I', :'service_database', :'service_user')
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = :'service_database')\gexec
SQL

  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
    --set=service_user="$service" --set=service_database="$database" <<'SQL'
REVOKE ALL ON DATABASE :"service_database" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"service_database" TO :"service_user";
SQL
}

for service in identity quiz classroom assessment community proctoring ai; do
  create_service_database "$service"
done

# pgvector is available for AI's future retrieval work, but no domain tables
# or vector repositories are created by the scaffold.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname ai_db \
  -c 'CREATE EXTENSION IF NOT EXISTS vector;'
