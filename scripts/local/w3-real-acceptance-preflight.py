#!/usr/bin/env python3
"""Read-only W3 real-acceptance host preflight. Never creates or stops resources.

This diagnoses host prerequisites only; it NEVER grants privacy/provider/production
approval or declares any real-browser or multi-service acceptance PASS.
"""

import argparse
import importlib.util
import json
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys


def run(argv, cwd=None):
    try:
        result = subprocess.run(argv, cwd=cwd, text=True, capture_output=True,
                                timeout=10, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return None
    return result.stdout.strip() if result.returncode == 0 else None


def port_free(port):
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            # Match application servers that use reusable-bind semantics. Without
            # this, their recently closed accepted connections can leave the port
            # in TIME_WAIT and produce a false "occupied" result after teardown.
            # A live IPv4 listener still makes bind fail, as the guard test proves.
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("127.0.0.1", port))
            listener.listen(1)
        return True
    except OSError:
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    repo = args.repo.resolve()
    checks = {}
    for name in ("git", "docker", "java", "node", "pnpm", "openssl"):
        checks[name] = shutil.which(name) is not None

    git_root = run(["git", "rev-parse", "--show-toplevel"], cwd=repo) if checks["git"] else None
    checks["git_checkout"] = git_root is not None and Path(git_root).resolve() == repo
    commit = run(["git", "rev-parse", "HEAD"], cwd=repo) if checks["git_checkout"] else None
    java_version = run(["java", "-version"]) if checks["java"] else None
    if not java_version and checks["java"]:
        try:
            java_version = subprocess.run(["java", "-version"], text=True,
                                          capture_output=True, timeout=10).stderr.strip()
        except (OSError, subprocess.TimeoutExpired):
            pass
    checks["java_21"] = bool(java_version and re.search(r'\b(?:openjdk|java) version "21[."]', java_version))
    checks["python_bcrypt"] = importlib.util.find_spec("bcrypt") is not None
    checks["w3a_runtime_present"] = (repo / "scripts/local/w3a-real-runtime.py").is_file()
    checks["w3_real_frontend_present"] = (repo / "frontend/tests/w3a-real-e2e/publication.spec.ts").is_file()

    docker_server = (run(["docker", "info", "--format", "{{.ServerVersion}}"])
                     if checks["docker"] else None)
    checks["docker_daemon"] = bool(docker_server)
    # These fixed ports are used by the legacy W3-A launcher; an isolated new
    # harness may use other ports. Never kill an existing listener to clear them.
    for port in (3000, 8080):
        checks[f"legacy_port_{port}_free"] = port_free(port)
    blocking = [key for key in ("git_checkout", "docker_daemon", "java_21", "node", "pnpm", "openssl", "python_bcrypt", "w3a_runtime_present") if not checks[key]]
    notes = []
    if not checks["legacy_port_8080_free"]:
        notes.append("Legacy W3-A launcher requires unused loopback 8080; do not terminate another listener")
    if not checks["legacy_port_3000_free"]:
        notes.append("Existing frontend on 3000 must not be reused without ownership verification")
    if checks["w3a_runtime_present"]:
        notes.append("W3-A launcher is historical and excludes Proctoring/LiveKit; validate its pinned backend SHA before reuse")
    notes.append("Host readiness is NOT a test authorization, production approval, or real E2E PASS")
    print(json.dumps({"status": "HOST_PREREQUISITES_BLOCKED" if blocking else "HOST_PREREQUISITES_AVAILABLE_ONLY",
                      "git_commit": commit, "checks": checks, "blocking": blocking, "notes": notes},
                     indent=2, sort_keys=True))
    return 2 if blocking else 0


if __name__ == "__main__":
    sys.exit(main())
