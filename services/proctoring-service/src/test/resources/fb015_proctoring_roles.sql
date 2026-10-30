-- TEST-ONLY disposable fixture provisioning (FB-015). Deployment migrations never
-- read this file; operators provision production roles/secrets (see the service
-- README and infrastructure/postgres/01-init-databases.sh).
CREATE ROLE quizopia_proctoring_owner NOLOGIN NOINHERIT;
CREATE ROLE quizopia_proctoring_custodian NOLOGIN NOINHERIT;
CREATE ROLE proctoring LOGIN PASSWORD 'proctoring_local_only' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
