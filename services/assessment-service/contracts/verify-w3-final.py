#!/usr/bin/env python3
"""Read-only acceptance audit; no credentials, database connections or dependency installation."""
from pathlib import Path
import hashlib
import os
import runpy
import subprocess
import sys
import xml.etree.ElementTree as ET
import yaml

root = Path(__file__).resolve().parents[3]
os.chdir(root)
baseline = "4a75be8f446493ad3dd5025a3930b42639cafecb"
previous = sys.argv
try:
    sys.argv = ["validate-w3b3.py", "--w3-final"]
    runpy.run_path("services/assessment-service/contracts/validate-w3b3.py", run_name="__main__")
finally:
    sys.argv = previous
protected = sorted(Path("services/assessment-service/src/main/resources/db/migration").glob("*.sql"))
protected += [Path(name) for name in [
    "docs/decisions/ADR-015-w3b-w3c-leader-policy.md",
    "services/assessment-service/contracts/publications.openapi.yaml",
    "services/identity-service/contracts/user-admission.openapi.yaml",
    "docs/specifications/proposed/w3b-w3c.openapi.yaml",
]]
for path in protected:
    content = path.read_bytes()
    assert content == subprocess.check_output(["git", "show", baseline + ":" + str(path)]), path
    print("PASS protected SHA256", path, hashlib.sha256(content).hexdigest())

def setting(document, path):
    if path in document:
        return document[path]
    for key in sorted(document, key=len, reverse=True):
        value = document[key]
        if path.startswith(key + ".") and isinstance(value, dict):
            try:
                return setting(value, path[len(key) + 1:])
            except AssertionError:
                continue
    raise AssertionError(path)

for module in ["gateway", "services/assessment-service"]:
    document = yaml.safe_load(Path(module + "/src/main/resources/application.yml").read_text())
    for name in ["quizopia.publication.enabled", "quizopia.admission.enabled",
                 "quizopia.assessment.features.attempts-enabled", "quizopia.assessment.features.results-enabled",
                 "quizopia.assessment.features.closure-enabled", "quizopia.assessment.closure.inbox-enabled"]:
        assert str(setting(document, name)).endswith(":false}"), (module, name)
    if module.endswith("assessment-service"):
        for name in ["worker-enabled", "edge-inbox-verified", "recipient-enabled"]:
            assert setting(document, "quizopia.assessment.closure." + name).endswith(":false}")
document = yaml.safe_load(Path("services/identity-service/src/main/resources/application.yml").read_text())
for name in ["quizopia.identity.user-admission.enabled", "quizopia.identity.notice-recipients.enabled"]:
    assert setting(document, name).endswith(":false}")
assert setting(document, "quizopia.identity.notice-recipients.require-secure-transport").endswith(":true}")
print("PASS default-off feature gates and default-required Identity HTTPS")
for module in ["services/assessment-service", "services/identity-service", "services/classroom-service", "services/quiz-service", "gateway"]:
    reports = list(Path(module + "/target/w3-final-verification/surefire-reports").glob("TEST-*.xml"))
    if not reports:
        print("NOT VERIFIED final report files absent:", module)
        continue
    totals = {key: sum(int(ET.parse(path).getroot().get(key, "0")) for path in reports)
              for key in ["tests", "failures", "errors", "skipped"]}
    assert totals["failures"] == totals["errors"] == totals["skipped"] == 0, (module, totals)
    print("PASS current local report totals", module, totals,
          "(run the shell wrapper for a complete fresh execution)")
