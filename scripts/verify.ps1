$ErrorActionPreference = 'Stop'

Push-Location (Join-Path $PSScriptRoot '..')
try {
  pnpm.cmd --dir frontend install --frozen-lockfile
  pnpm.cmd --dir frontend lint
  pnpm.cmd --dir frontend format:check
  pnpm.cmd --dir frontend typecheck
  pnpm.cmd --dir frontend test
  pnpm.cmd --dir frontend build

  $projects = @('gateway', 'services/identity-service', 'services/quiz-service',
    'services/classroom-service', 'services/assessment-service',
    'services/community-service', 'services/proctoring-service', 'services/ai-service')
  foreach ($project in $projects) {
    Push-Location $project
    try { .\mvnw.cmd -B verify } finally { Pop-Location }
  }
} finally {
  Pop-Location
}
