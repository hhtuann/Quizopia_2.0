#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend lint
pnpm --dir frontend format:check
pnpm --dir frontend typecheck
pnpm --dir frontend test
pnpm --dir frontend build

projects=(gateway services/identity-service services/quiz-service services/classroom-service services/assessment-service services/community-service services/proctoring-service services/ai-service)
for project in "${projects[@]}"; do
  (cd "$project" && ./mvnw -B verify)
done
