#!/usr/bin/env bash
# Full local verification. Destructive drills are confined to test-owned containers.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../../.."
java -version 2>&1 | head -1 | rg -q 'version "21\.'
docker info >/dev/null
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx256m -XX:ActiveProcessorCount=2}"
evidence=gateway/target/w3-final-evidence
mkdir -p "$evidence"
for module in services/assessment-service services/identity-service services/classroom-service services/quiz-service gateway; do
  options=()
  if [ "$module" = gateway ]; then options+=(-DreuseForks=false -DforkCount=1); fi
  log="$evidence/$(basename "$module").log"
  if bash "$module/mvnw" -B -f "$module/pom.xml" \
    -Dquizopia.build.directory=target/w3-final-verification spotless:check verify \
    -DargLine='-Xmx384m -XX:ActiveProcessorCount=2' "${options[@]}" >"$log" 2>&1; then
    printf '%s exit=0 log=%s\n' "$module" "$log"
  else
    result=$?
    printf '%s exit=%s log=%s\n' "$module" "$result" "$log"
    exit "$result"
  fi
done
python3 services/assessment-service/contracts/verify-w3-final.py
git diff --check
