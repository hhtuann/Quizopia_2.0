-- TEST-ONLY disposable fixture provisioning (FB-015). Deployment migrations never
-- read this file; operators provision production roles/secrets (see the service
-- README and infrastructure/postgres/01-init-databases.sh).
CREATE ROLE quizopia_assessment_owner NOLOGIN NOINHERIT;
CREATE ROLE quizopia_assessment_custodian NOLOGIN NOINHERIT;
CREATE ROLE assessment LOGIN PASSWORD 'assessment_local_only' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
