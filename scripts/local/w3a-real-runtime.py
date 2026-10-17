#!/usr/bin/env python3
"""Disposable W3-A browser acceptance topology; no shared or production resources.

Uses existing LD-05 ownership/lifecycle helpers. Credentials and logs stay in a
private temporary directory. Requires Java 21, Docker, Maven cache, OpenSSL and
Python bcrypt. Prints only non-secret endpoint metadata and cleanup instructions.
"""

import argparse
import base64
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import signal
import subprocess
import sys
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
BACKEND = os.environ.get("W3_BACKEND_SHA", "e8bd838092b82a0de1ed652b1fce59ef019f2b32")
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("owned_runtime", ROOT / "scripts/local/ld05-real-executor-runtime.py")
owned = importlib.util.module_from_spec(spec)
spec.loader.exec_module(owned)


def process_owned(directory, name):
    state = json.loads((directory / "state.json").read_text())
    if state.get("fixture") != "w3a-real-browser-v1":
        raise RuntimeError("Not an owned W3-A runtime")
    jar = state["service_jars"][name]
    process = next((item for item in state["processes"] if item["jar"] == jar), None)
    if process is None:
        raise RuntimeError("Runtime process record is incomplete")
    path = Path("/proc") / str(process["pid"])
    try:
        status_text = (path / "status").read_text()
        for line in status_text.splitlines():
            if line.startswith("State:"):
                state_code = line.split(":", 1)[1].strip()
                if state_code.startswith(("Z", "X")):
                    raise ProcessLookupError("Runtime process is not running")
                break
        cmdline = (path / "cmdline").read_bytes().split(b"\0")
        cwd = (path / "cwd").resolve()
    except (FileNotFoundError, ProcessLookupError):
        raise ProcessLookupError("Runtime process is not running")
    except PermissionError:
        raise RuntimeError("Runtime process ownership does not match")
    if jar.encode() not in cmdline or cwd != directory:
        raise RuntimeError("Runtime process ownership does not match")
    return process["pid"]


def fault(directory, action):
    directory = directory.resolve()
    mode, service = action.split("-", 1)
    if mode not in {"pause", "resume"} or service not in {"quiz", "gateway", "assessment"}:
        raise ValueError("Unsupported owned fault")
    pid = process_owned(directory, service)
    # SIGCONT is safe and idempotent. Only the fixture's exact process is eligible.
    os.kill(pid, signal.SIGSTOP if mode == "pause" else signal.SIGCONT)
    state = json.loads((directory / "state.json").read_text())
    state[service + "_paused"] = mode == "pause"
    owned.state_write(directory, state)
    print("Owned", service, "process", "paused" if mode == "pause" else "resumed")


def wait_for_exit(directory, service, timeout=5):
    """Bound cleanup without assuming SIGTERM synchronously stops Java."""
    deadline = time.monotonic() + timeout
    while True:
        try:
            process_owned(directory, service)
        except (FileNotFoundError, ProcessLookupError):
            return True
        except RuntimeError:
            # Changed ownership is left untouched and cannot prove clean exit.
            return False
        if time.monotonic() >= deadline:
            return False
        time.sleep(0.05)


def down(directory):
    directory = directory.resolve()
    state = json.loads((directory / "state.json").read_text())
    if state.get("fixture") != "w3a-real-browser-v1":
        raise RuntimeError("Not an owned W3-A runtime")
    unverified = []
    for service in state["service_jars"]:
        try:
            pid = process_owned(directory, service)
            if state.get(service + "_paused"):
                os.kill(pid, signal.SIGCONT)
            os.kill(pid, signal.SIGTERM)
            if not wait_for_exit(directory, service):
                unverified.append(service)
        except (FileNotFoundError, ProcessLookupError):
            # An already exited process needs no signal and must not prevent
            # cleanup of the remaining independently verified owned resources.
            pass
        except (OSError, RuntimeError):
            # A reused PID or changed working directory is left untouched.
            unverified.append(service)
    for container in reversed(state["containers"]):
        inspection = subprocess.run(
            ["docker", "inspect", "--format", '{{ index .Config.Labels "quizopia.owner" }}', container],
            capture_output=True, text=True,
        )
        if inspection.returncode != 0:
            # Inspect failure cannot distinguish removal from daemon outage.
            unverified.append(container)
            continue
        if inspection.stdout.strip() != state["resource_prefix"]:
            unverified.append(container)
            continue
        try:
            owned.run(["docker", "rm", "-f", "-v", container], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        except subprocess.CalledProcessError:
            unverified.append(container)
    state["cleanup_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    state["cleanup_unverified"] = unverified
    if not unverified:
        state["stopped_at"] = state["cleanup_at"]
    else:
        state.pop("stopped_at", None)
    owned.state_write(directory, state)
    print("Owned runtime cleanup", "attempted;" if unverified else "complete;", "private evidence retained at", directory)
    if unverified:
        print("Unverified resources left untouched:", len(unverified))


def seed_membership(directory, classroom_id, student_id, teacher_id):
    """Seed only a disposable fixture membership; this is not a public join API."""
    directory = directory.resolve()
    state = json.loads((directory / "state.json").read_text())
    if state.get("fixture") != "w3a-real-browser-v1":
        raise RuntimeError("Not an owned W3-A runtime")
    for value in [classroom_id, student_id, teacher_id]:
        if str(uuid.UUID(value)) != value:
            raise ValueError("Fixture identifiers must be canonical UUIDs")
    container = state["database_containers"]["classroom"]
    label = subprocess.check_output(["docker", "inspect", "--format", '{{ index .Config.Labels "quizopia.owner" }}', container], text=True).strip()
    if label != state["resource_prefix"] or container not in state["containers"]:
        raise RuntimeError("Classroom database ownership does not match")
    # The exact owner/ID must exist through real Classroom creation first. UUIDs
    # are validated above before interpolation, and the transaction fails closed.
    sql = f"""BEGIN;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM classrooms WHERE id='{classroom_id}' AND owner_teacher_user_id='{teacher_id}') THEN
    RAISE EXCEPTION 'Owned fixture Classroom not found';
  END IF;
END $$;
INSERT INTO classroom_memberships(id,classroom_id,user_id)
VALUES('{uuid.uuid4()}','{classroom_id}','{student_id}')
ON CONFLICT(classroom_id,user_id) DO NOTHING;
COMMIT;"""
    owned.run(["docker", "exec", "-i", container, "psql", "-U", "classroom", "-d", "classroom_db", "-v", "ON_ERROR_STOP=1"],
              input=sql.encode(), stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    print("Owned disposable Classroom membership fixture ready")


def remove_membership(directory, classroom_id, student_id, teacher_id):
    """Remove only a disposable fixture membership to verify former-member access."""
    directory = directory.resolve()
    state = json.loads((directory / "state.json").read_text())
    if state.get("fixture") != "w3a-real-browser-v1":
        raise RuntimeError("Not an owned W3-A runtime")
    for value in [classroom_id, student_id, teacher_id]:
        if str(uuid.UUID(value)) != value:
            raise ValueError("Fixture identifiers must be canonical UUIDs")
    container = state["database_containers"]["classroom"]
    label = subprocess.check_output(["docker", "inspect", "--format", '{{ index .Config.Labels "quizopia.owner" }}', container], text=True).strip()
    if label != state["resource_prefix"] or container not in state["containers"]:
        raise RuntimeError("Classroom database ownership does not match")
    sql = f"""BEGIN;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM classrooms WHERE id='{classroom_id}' AND owner_teacher_user_id='{teacher_id}') THEN
    RAISE EXCEPTION 'Owned fixture Classroom not found';
  END IF;
END $$;
DELETE FROM classroom_memberships WHERE classroom_id='{classroom_id}' AND user_id='{student_id}';
COMMIT;"""
    owned.run(["docker", "exec", "-i", container, "psql", "-U", "classroom", "-d", "classroom_db", "-v", "ON_ERROR_STOP=1"],
              input=sql.encode(), stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    print("Owned disposable Classroom membership removed")


def probe_recipient_eligibility(directory, student_id):
    """Verify Identity recipient-eligibility contract and least-privilege scope in real topology."""
    import urllib.error
    import urllib.parse
    import urllib.request

    directory = directory.resolve()
    state = json.loads((directory / "state.json").read_text())
    if state.get("fixture") != "w3a-real-browser-v1":
        raise RuntimeError("Not an owned W3-A runtime")
    if str(uuid.UUID(student_id)) != student_id:
        raise ValueError("Student identifier must be a canonical UUID")

    assessment_env = dict(line.split("=", 1) for line in (directory / "assessment.env").read_text().splitlines() if "=" in line)
    gateway_env = dict(line.split("=", 1) for line in (directory / "gateway.env").read_text().splitlines() if "=" in line)
    issuer = state["endpoints"]["identity"]

    def get_token(client_id, secret, scope):
        req = urllib.request.Request(f"{issuer}/oauth2/token", method="POST")
        auth_header = base64.b64encode(f"{client_id}:{secret}".encode()).decode()
        req.add_header("Authorization", f"Basic {auth_header}")
        req.add_header("Content-Type", "application/x-www-form-urlencoded")
        data = urllib.parse.urlencode({"grant_type": "client_credentials", "scope": scope}).encode()
        try:
            with urllib.request.urlopen(req, data=data) as resp:
                body = json.loads(resp.read().decode())
                return resp.status, body.get("access_token")
        except urllib.error.HTTPError as err:
            return err.code, None

    def call_eligibility(token, target_id):
        req = urllib.request.Request(f"{issuer}/internal/security/notice-recipients/{target_id}/eligibility", method="POST")
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        req.add_header("Accept", "application/json")
        try:
            with urllib.request.urlopen(req, data=b"") as resp:
                return resp.status, json.loads(resp.read().decode())
        except urllib.error.HTTPError as err:
            return err.code, None

    status, token = get_token(
        assessment_env["USER_ADMISSION_CLIENT_ID"],
        assessment_env["USER_ADMISSION_CLIENT_SECRET"],
        "identity.notice-recipient.read",
    )
    if status != 200 or not token:
        raise RuntimeError(f"Failed to obtain assessment-service token with recipient scope: HTTP {status}")

    call_status, result = call_eligibility(token, student_id)
    if call_status != 200 or not result.get("eligible"):
        raise RuntimeError(f"Recipient eligibility check failed for student {student_id}: HTTP {call_status}, body {result}")

    non_existent = str(uuid.uuid4())
    call_status_ne, result_ne = call_eligibility(token, non_existent)
    if call_status_ne not in {200, 503}:
        raise RuntimeError(f"Recipient eligibility for non-existent student returned unexpected HTTP {call_status_ne}")
    if call_status_ne == 200 and result_ne.get("eligible") is not False:
        raise RuntimeError(f"Recipient eligibility should return eligible=false for non-existent student: HTTP {call_status_ne}, body {result_ne}")

    call_status_unauth, _ = call_eligibility(None, student_id)
    if call_status_unauth != 401:
        raise RuntimeError(f"Unauthenticated eligibility call should return 401, got {call_status_unauth}")

    gw_status, _ = get_token(
        gateway_env["USER_ADMISSION_CLIENT_ID"],
        gateway_env["USER_ADMISSION_CLIENT_SECRET"],
        "identity.notice-recipient.read",
    )
    if gw_status == 200:
        raise RuntimeError("Gateway service client must NOT be granted identity.notice-recipient.read scope")

    output = {
        "status": "VERIFIED",
        "studentId": student_id,
        "eligible": result["eligible"],
        "checkedAt": result["checkedAt"],
        "least_privilege_verified": True,
    }
    print(json.dumps(output))


def creation_receipt_count(directory, owner_id, key_digest):
    """Observe an actual committed receipt in only the owned Assessment fixture."""
    directory = directory.resolve()
    state = json.loads((directory / "state.json").read_text())
    if state.get("fixture") != "w3a-real-browser-v1":
        raise RuntimeError("Not an owned W3-A runtime")
    if str(uuid.UUID(owner_id)) != owner_id or not re.fullmatch("[0-9a-f]{64}", key_digest):
        raise ValueError("Canonical fixture owner and SHA-256 digest are required")
    container = state["database_containers"]["assessment"]
    label = subprocess.check_output(["docker", "inspect", "--format", '{{ index .Config.Labels "quizopia.owner" }}', container], text=True).strip()
    if label != state["resource_prefix"] or container not in state["containers"]:
        raise RuntimeError("Assessment database ownership does not match")
    sql = f"BEGIN READ ONLY; SELECT count(*) FROM publication_creations WHERE owner_id='{owner_id}' AND key_digest='{key_digest}'; COMMIT;"
    result = owned.run(["docker", "exec", "-i", container, "psql", "-qAt", "-U", "assessment", "-d", "assessment_db", "-v", "ON_ERROR_STOP=1"],
                       input=sql, capture_output=True, text=True)
    count = result.stdout.strip()
    if count not in {"0", "1"}:
        raise RuntimeError("Unexpected owned fixture receipt count")
    print(count)


def quiz_pending_request(directory):
    """Read only socket queue sizes; never inspect HTTP headers or payloads."""
    directory = directory.resolve()
    pid = process_owned(directory, "quiz")
    state = json.loads((directory / "state.json").read_text())
    port = int(state["endpoints"]["quiz"].rsplit(":", 1)[1])
    result = owned.run(["ss", "-tnp", f"( sport = :{port} )"], capture_output=True, text=True)
    pending = any(
        len(fields := line.split()) >= 5
        and fields[0] == "ESTAB"
        and fields[1].isdecimal()
        and int(fields[1]) > 0
        and f"pid={pid}," in line
        for line in result.stdout.splitlines()
    )
    if process_owned(directory, "quiz") != pid:
        raise RuntimeError("Quiz process changed during queue observation")
    print("1" if pending else "0")


def _restart_assessment(directory, env_updates, state_updates, message):
    directory = directory.resolve()
    state = json.loads((directory / "state.json").read_text())
    if state.get("fixture") != "w3a-real-browser-v1":
        raise RuntimeError("Not an owned W3-A runtime")
    jar = state["service_jars"]["assessment"]

    # 1. Initial process ownership verification.
    # MUST FAIL CLOSED on ownership mismatch or incomplete process record.
    pid = process_owned(directory, "assessment")

    # 2. Stop the existing verified process.
    if state.get("assessment_paused"):
        os.kill(pid, signal.SIGCONT)
    os.kill(pid, signal.SIGTERM)

    if not wait_for_exit(directory, "assessment", timeout=10):
        # 3. Process did not exit within timeout.
        # NEVER SIGKILL without revalidating process identity!
        try:
            current_pid = process_owned(directory, "assessment")
        except ProcessLookupError:
            # Process exited right around the deadline; no kill needed.
            current_pid = None
        except RuntimeError as err:
            # Ownership mismatch or process identity replaced!
            # MUST NOT SIGKILL and MUST NOT launch replacement!
            raise RuntimeError("Assessment process ownership mismatch before SIGKILL") from err

        if current_pid is not None:
            if current_pid != pid:
                raise RuntimeError("Assessment process PID changed unexpectedly before SIGKILL")
            os.kill(pid, signal.SIGKILL)
            if not wait_for_exit(directory, "assessment", timeout=5):
                raise RuntimeError("Assessment process failed to stop cleanly after SIGKILL")

    # 4. Process has exited cleanly. Update configuration and launch replacement.
    env_path = directory / "assessment.env"
    env_vars = dict(line.split("=", 1) for line in env_path.read_text().splitlines() if "=" in line)
    env_vars.update(env_updates)
    owned.private_write(env_path, "\n".join(f"{k}={v}" for k, v in env_vars.items()) + "\n")

    log = (directory / "assessment.log").open("ab")
    process = subprocess.Popen(
        ["java", "-Xmx256m", "-XX:ActiveProcessorCount=2", "-jar", str(jar)],
        env={**os.environ, **env_vars}, cwd=directory, stdout=log, stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    log.close()

    state["processes"] = [p for p in state["processes"] if p["jar"] != jar]
    state["processes"].append({"pid": process.pid, "jar": jar})
    state["test_only_flags"].update(state_updates)
    owned.state_write(directory, state)
    owned.wait_http(state["endpoints"]["assessment"] + "/actuator/health")
    print(message, flush=True)


def activate_edge_attestation(directory):
    """Operator-controlled transition: restart owned assessment-service with edge inbox attestation enabled, keeping closure worker OFF."""
    _restart_assessment(
        directory,
        {
            "ASSESSMENT_EDGE_INBOX_VERIFIED": "true",
            "ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED": "true",
            "ASSESSMENT_CLOSURE_WORKER_ENABLED": "false",
        },
        {
            "ASSESSMENT_EDGE_INBOX_VERIFIED": "true",
            "ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED": "true",
            "ASSESSMENT_CLOSURE_WORKER_ENABLED": "false",
        },
        "PASS operator activation: edge inbox attestation activated; closure worker remains OFF",
    )


def activate_closure_worker(directory, poll_millis="4000"):
    """Operator-controlled transition: restart owned assessment-service with worker and edge attestation enabled."""
    _restart_assessment(
        directory,
        {
            "ASSESSMENT_EDGE_INBOX_VERIFIED": "true",
            "ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED": "true",
            "ASSESSMENT_CLOSURE_WORKER_ENABLED": "true",
            "QUIZOPIA_ASSESSMENT_CLOSURE_POLL_MILLIS": str(poll_millis),
        },
        {
            "ASSESSMENT_EDGE_INBOX_VERIFIED": "true",
            "ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED": "true",
            "ASSESSMENT_CLOSURE_WORKER_ENABLED": "true",
            "QUIZOPIA_ASSESSMENT_CLOSURE_POLL_MILLIS": str(poll_millis),
        },
        f"PASS operator activation: assessment closure worker and edge attestation activated (poll_millis={poll_millis})",
    )


def reset_closure_flags(directory):
    """Operator-controlled transition: restart owned assessment-service resetting closure flags to pre-worker/pre-attestation state."""
    _restart_assessment(
        directory,
        {
            "ASSESSMENT_EDGE_INBOX_VERIFIED": "false",
            "ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED": "false",
            "ASSESSMENT_CLOSURE_WORKER_ENABLED": "false",
            "QUIZOPIA_ASSESSMENT_CLOSURE_POLL_MILLIS": "5000",
        },
        {
            "ASSESSMENT_EDGE_INBOX_VERIFIED": "false",
            "ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED": "false",
            "ASSESSMENT_CLOSURE_WORKER_ENABLED": "false",
            "QUIZOPIA_ASSESSMENT_CLOSURE_POLL_MILLIS": "5000",
        },
        "PASS operator reset: closure flags returned to initial pre-worker state",
    )


def up():
    import bcrypt

    os.umask(0o077)
    try:
        gateway_port = int(os.environ.get("W3A_REAL_GATEWAY_PORT", "8080"))
        owned.available("127.0.0.1", gateway_port)
    except OSError:
        gateway_port = owned.available("127.0.0.1", 0)
    directory = Path(tempfile.mkdtemp(prefix="quizopia-w3a-"))
    state = {"fixture": "w3a-real-browser-v1", "backend_sha": BACKEND,
             "resource_prefix": "quizopia-w3a-" + secrets.token_hex(5),
             "containers": [], "processes": [], "artifacts": {}, "service_jars": {},
             "started_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
    owned.state_write(directory, state)
    print("STATE_DIRECTORY=" + str(directory), flush=True)
    try:
        snapshot = directory / "backend"
        modules = ["services/identity-service", "services/quiz-service", "services/classroom-service", "services/assessment-service", "gateway"]
        owned.archive(BACKEND, modules, snapshot)
        build_env = {**os.environ, "MAVEN_USER_HOME": str(Path.home() / ".local/share/quizopia-dev/maven"),
                     "MAVEN_OPTS": "-Xmx256m -XX:ActiveProcessorCount=2"}
        for module in modules:
            with (directory / (module.split("/")[-1] + "-build.log")).open("wb") as log:
                owned.run(["bash", str(snapshot / module / "mvnw"), "-B", "-f", str(snapshot / module / "pom.xml"),
                           "-DskipTests", "package"], env=build_env, stdout=log, stderr=subprocess.STDOUT)
            print("Built pinned snapshot:", module, BACKEND, flush=True)

        databases = {}
        for name in ["identity", "quiz", "classroom", "assessment"]:
            port = owned.available("127.0.0.1", 0)
            password = secrets.token_urlsafe(32)
            container = owned.container(directory, state, name + "-postgres", "postgres:17-alpine",
                                        [f"127.0.0.1:{port}:5432"],
                                        {"POSTGRES_DB": name + "_db", "POSTGRES_USER": name, "POSTGRES_PASSWORD": password})
            databases[name] = {"container": container, "port": port, "password": password}
            for attempt in range(60):
                if subprocess.run(["docker", "exec", container, "pg_isready", "-U", name], stdout=subprocess.DEVNULL,
                                  stderr=subprocess.DEVNULL).returncode == 0:
                    break
                time.sleep(1)
            else:
                raise RuntimeError("Owned PostgreSQL readiness failed: " + name)
        redis_port, smtp_port, mailpit_port = [owned.available("127.0.0.1", 0) for _ in range(3)]
        owned.container(directory, state, "redis", "redis:8.2-alpine", [f"127.0.0.1:{redis_port}:6379"])
        owned.container(directory, state, "mailpit", "axllent/mailpit:v1.27.8",
                        [f"127.0.0.1:{smtp_port}:1025", f"127.0.0.1:{mailpit_port}:8025"])
        keys = directory / "keys"
        keys.mkdir()
        owned.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(keys / "private.pem")],
                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        owned.run(["openssl", "pkey", "-in", str(keys / "private.pem"), "-pubout", "-out", str(keys / "public.pem")],
                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        endpoints = {name: "http://127.0.0.1:" + str(owned.available("127.0.0.1", 0))
                     for name in ["identity", "quiz", "classroom", "assessment"]}
        endpoints.update({"gateway": f"http://127.0.0.1:{gateway_port}", "mailpit": "http://127.0.0.1:" + str(mailpit_port)})
        state["endpoints"] = endpoints
        state["database_containers"] = {name: values["container"] for name, values in databases.items()}
        owned.state_write(directory, state)
        cursor_key = base64.b64encode(secrets.token_bytes(32)).decode()
        issuer = endpoints["identity"]
        browser_origin = os.environ.get("W3A_REAL_FRONTEND_URL", "http://localhost:3000")
        closure_worker = os.environ.get("W3A_ASSESSMENT_CLOSURE_WORKER_ENABLED", "false")
        edge_inbox_verified = os.environ.get("W3A_ASSESSMENT_EDGE_INBOX_VERIFIED", "false")
        recipient_eligibility = os.environ.get("W3A_ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED", "false")
        identity_notice_recipients = os.environ.get("W3A_IDENTITY_NOTICE_RECIPIENTS_ENABLED", "false")
        inbox_enabled = os.environ.get("W3A_ASSESSMENT_INBOX_ENABLED", "true")
        closure_poll_millis = os.environ.get("W3A_ASSESSMENT_CLOSURE_POLL_MILLIS", "500")

        common = {"SPRING_PROFILES_ACTIVE": "local", "SERVER_ADDRESS": "127.0.0.1", "REDIS_HOST": "127.0.0.1", "REDIS_PORT": str(redis_port),
                  "ASSESSMENT_INBOX_ENABLED": inbox_enabled, "ASSESSMENT_CLOSURE_WORKER_ENABLED": closure_worker,
                  "ASSESSMENT_EDGE_INBOX_VERIFIED": edge_inbox_verified, "ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED": recipient_eligibility,
                  "ASSESSMENT_MONITORING_CAPTURE_ENABLED": "false", "ASSESSMENT_MONITORING_PUBLISHER_ENABLED": "false",
                  "PROCTORING_ENABLED": "false", "PROCTORING_MEDIA_ENABLED": "false",
                  "IDENTITY_ISSUER": issuer, "IDENTITY_JWKS_URI": issuer + "/oauth2/jwks", "GATEWAY_ALLOWED_ORIGINS": browser_origin}

        def launch(name, extra):
            module = "gateway" if name == "gateway" else "services/" + name + "-service"
            jar = snapshot / module / "target" / ("quizopia-" + name + "-0.1.0-SNAPSHOT.jar")
            environment = {**common, "SERVER_PORT": endpoints[name].rsplit(":", 1)[1]}
            if name in databases:
                db = databases[name]
                environment.update({"DB_URL": f"jdbc:postgresql://127.0.0.1:{db['port']}/{name}_db",
                                    "DB_USERNAME": name, "DB_PASSWORD": db["password"]})
            environment.update(extra)
            # Environment snapshot is private and deliberately excluded from public evidence.
            owned.private_write(directory / (name + ".env"), "\n".join(k + "=" + v for k, v in environment.items()) + "\n")
            state["service_jars"][name] = str(jar)
            owned.java_process(directory, state, name, jar, environment)
            owned.wait_http(endpoints[name] + ("/oauth2/jwks" if name == "identity" else "/actuator/health"))
            print("Ready:", name, endpoints[name], flush=True)

        launch("identity", {"MAIL_HOST": "127.0.0.1", "MAIL_PORT": str(smtp_port), "IDENTITY_AUTHORIZATION_SERVER_ENABLED": "true",
                            "IDENTITY_SIGNING_KEY_KID": state["resource_prefix"], "IDENTITY_SIGNING_KEY_PRIVATE_KEY_PATH": str(keys / "private.pem"),
                            "IDENTITY_SIGNING_KEY_PUBLIC_KEY_PATH": str(keys / "public.pem"), "IDENTITY_EMAIL_OUTBOX_ACTIVE_KEY_VERSION": "v1",
                            "QUIZOPIA_IDENTITY_EMAIL_OUTBOX_ENCRYPTION_KEYS_V1": base64.b64encode(secrets.token_bytes(32)).decode(),
                            "IDENTITY_EMAIL_OUTBOX_DISPATCHER_ENABLED": "true", "IDENTITY_EMAIL_OUTBOX_POLLING_INTERVAL": "PT1S",
                            "IDENTITY_USER_ADMISSION_ENABLED": "true", "IDENTITY_USER_ADMISSION_ALLOWED_CLIENTS": "gateway,quiz-service,classroom-service,assessment-service",
                            "IDENTITY_USER_ADMISSION_REQUIRE_SECURE_TRANSPORT": "false", "IDENTITY_AUTHORITY_RECONCILIATION_ENABLED": "true",
                            "IDENTITY_AUTHORITY_POLLING_INTERVAL": "PT1S",
                            "IDENTITY_NOTICE_RECIPIENTS_ENABLED": identity_notice_recipients,
                            "IDENTITY_NOTICE_RECIPIENTS_REQUIRE_SECURE_TRANSPORT": "false",
                            "IDENTITY_NOTICE_RECIPIENTS_ASSESSMENT_CLIENT_ID": "assessment-service"})
        clients = {name: secrets.token_urlsafe(32) for name in ["gateway", "quiz-service", "classroom-service", "assessment-service"]}
        scopes = {name: ["identity.user-admission"] for name in clients}
        scopes["assessment-service"] += ["quiz.version-snapshot.read", "classroom.teacher-authority.read", "classroom.membership.read"]
        if identity_notice_recipients == "true":
            scopes["assessment-service"].append("identity.notice-recipient.read")
        sql = []
        for client, secret in clients.items():
            client_id = str(uuid.uuid4())
            hashed = "{bcrypt}" + bcrypt.hashpw(secret.encode(), bcrypt.gensalt()).decode()
            sql.append(f"INSERT INTO oauth2_service_client(id,client_id,client_secret_hash,enabled,created_at,updated_at) VALUES ('{client_id}','{client}','{hashed}',true,now(),now());")
            sql += [f"INSERT INTO oauth2_service_client_scope(service_client_id,scope) VALUES ('{client_id}','{scope}');" for scope in scopes[client]]
        owned.run(["docker", "exec", "-i", databases["identity"]["container"], "psql", "-U", "identity", "-d", "identity_db", "-v", "ON_ERROR_STOP=1"],
                  input="\n".join(sql).encode(), stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)

        def admission(client):
            return {"USER_ADMISSION_ENABLED": "true", "USER_ADMISSION_IDENTITY_BASE_URL": issuer, "USER_ADMISSION_CLIENT_ID": client,
                    "USER_ADMISSION_CLIENT_SECRET": clients[client], "USER_ADMISSION_ALLOW_INSECURE_LOOPBACK": "true"}

        authority = {"INTERNAL_AUTHORITY_ENABLED": "true", "INTERNAL_AUTHORITY_REQUIRE_TLS": "false", "ASSESSMENT_SERVICE_CLIENT_ID": "assessment-service"}
        for name in ["quiz", "classroom"]:
            launch(name, {**admission(name + "-service"), **authority})
        features = {"PUBLICATION_ENABLED": "true", "ATTEMPTS_ENABLED": "true", "RESULTS_ENABLED": "true", "CLOSURE_ENABLED": "true"}
        launch("assessment", {**admission("assessment-service"), **features, "PUBLICATION_SOURCES_ENABLED": "true",
                              "PUBLICATION_QUIZ_BASE_URL": endpoints["quiz"], "PUBLICATION_CLASSROOM_BASE_URL": endpoints["classroom"],
                              "ASSESSMENT_EDGE_BASE_URL": endpoints["gateway"],
                              "ASSESSMENT_INBOX_ENABLED": inbox_enabled,
                              "ASSESSMENT_CLOSURE_WORKER_ENABLED": closure_worker,
                              "ASSESSMENT_EDGE_INBOX_VERIFIED": edge_inbox_verified,
                              "ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED": recipient_eligibility,
                              "QUIZOPIA_ASSESSMENT_CLOSURE_POLL_MILLIS": closure_poll_millis,
                              "ASSESSMENT_CURSOR_ACTIVE_KEY": "v1",
                              "ASSESSMENT_CURSOR_KEY_V1": cursor_key})
        launch("gateway", {**admission("gateway"), **features, "IDENTITY_SERVICE_URL": issuer,
                           "QUIZ_SERVICE_URL": endpoints["quiz"], "CLASSROOM_SERVICE_URL": endpoints["classroom"], "ASSESSMENT_SERVICE_URL": endpoints["assessment"],
                           "ASSESSMENT_INBOX_ENABLED": inbox_enabled})
        state["configured_client_scopes"] = scopes
        state["test_only_flags"] = {**features, **authority, "PUBLICATION_SOURCES_ENABLED": "true", "USER_ADMISSION_ENABLED": "true",
                                    "USER_ADMISSION_ALLOW_INSECURE_LOOPBACK": "true", "IDENTITY_USER_ADMISSION_REQUIRE_SECURE_TRANSPORT": "false",
                                    "ASSESSMENT_INBOX_ENABLED": inbox_enabled, "ASSESSMENT_CLOSURE_WORKER_ENABLED": closure_worker,
                                    "ASSESSMENT_EDGE_INBOX_VERIFIED": edge_inbox_verified, "ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED": recipient_eligibility,
                                    "IDENTITY_NOTICE_RECIPIENTS_ENABLED": identity_notice_recipients, "IDENTITY_NOTICE_RECIPIENTS_REQUIRE_SECURE_TRANSPORT": "false"}
        state["browser_origin"] = browser_origin
        state["ready_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        owned.state_write(directory, state)
        print("PASS isolated five-service readiness; USER TTL unchanged PT5M; attempts/results/closure/inbox contact enabled; closure worker/edge attestation defaulted OFF; monitoring/media disabled", flush=True)
        print("Cleanup: python3 scripts/local/w3a-real-runtime.py down " + str(directory), flush=True)
    except BaseException:
        down(directory)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    sub.add_parser("up")
    for name in ["down", "pause-quiz", "resume-quiz", "pause-gateway", "resume-gateway", "pause-assessment", "resume-assessment", "quiz-pending-request"]:
        sub.add_parser(name).add_argument("state_directory", type=Path)
    membership = sub.add_parser("seed-membership")
    membership.add_argument("state_directory", type=Path)
    membership.add_argument("classroom_id")
    membership.add_argument("student_id")
    membership.add_argument("teacher_id")
    membership_rm = sub.add_parser("remove-membership")
    membership_rm.add_argument("state_directory", type=Path)
    membership_rm.add_argument("classroom_id")
    membership_rm.add_argument("student_id")
    membership_rm.add_argument("teacher_id")
    eligibility = sub.add_parser("probe-recipient-eligibility")
    eligibility.add_argument("state_directory", type=Path)
    eligibility.add_argument("student_id")
    edge_attest = sub.add_parser("activate-edge-attestation")
    edge_attest.add_argument("state_directory", type=Path)
    activate = sub.add_parser("activate-closure-worker")
    activate.add_argument("state_directory", type=Path)
    activate.add_argument("poll_millis", nargs="?", default="4000")
    reset_flags = sub.add_parser("reset-closure-flags")
    reset_flags.add_argument("state_directory", type=Path)
    receipt = sub.add_parser("creation-receipt-count")
    receipt.add_argument("state_directory", type=Path)
    receipt.add_argument("owner_id")
    receipt.add_argument("key_digest")
    args = parser.parse_args()
    if args.action == "up":
        up()
    elif args.action == "down":
        down(args.state_directory)
    elif args.action == "seed-membership":
        seed_membership(args.state_directory, args.classroom_id, args.student_id, args.teacher_id)
    elif args.action == "remove-membership":
        remove_membership(args.state_directory, args.classroom_id, args.student_id, args.teacher_id)
    elif args.action == "probe-recipient-eligibility":
        probe_recipient_eligibility(args.state_directory, args.student_id)
    elif args.action == "activate-edge-attestation":
        activate_edge_attestation(args.state_directory)
    elif args.action == "activate-closure-worker":
        activate_closure_worker(args.state_directory, args.poll_millis)
    elif args.action == "reset-closure-flags":
        reset_closure_flags(args.state_directory)
    elif args.action == "creation-receipt-count":
        creation_receipt_count(args.state_directory, args.owner_id, args.key_digest)
    elif args.action == "quiz-pending-request":
        quiz_pending_request(args.state_directory)
    else:
        fault(args.state_directory, args.action)


if __name__ == "__main__":
    main()
