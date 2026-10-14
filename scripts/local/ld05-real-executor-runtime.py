#!/usr/bin/env python3
"""Owned, loopback-only LD-05 runtime; no production settings or existing processes changed.

Requires Java 21, Docker, Maven wrapper cache, Node/Corepack, existing frontend
node_modules/browser installation, and Python bcrypt. Secrets/logs stay in a private
temporary directory. `down STATE_DIRECTORY` only removes resources recorded there.
"""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
import urllib.parse
import uuid

ROOT = Path(__file__).resolve().parents[2]
BACKEND = "e5c4ef3500f1ce40303af161cd6ca74b706c21b2"
FRONTEND = "efd3f8b1964b4ad6e3de3847fac5c5e33d7d87bb"


def run(args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def available(address, port):
    family = socket.AF_INET6 if ":" in address else socket.AF_INET
    with socket.socket(family) as listener:
        if family == socket.AF_INET6:
            listener.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
        listener.bind((address, port))
        return listener.getsockname()[1]


def private_write(path, content):
    path.write_text(content)
    path.chmod(0o600)


def state_write(directory, state):
    private_write(directory / "state.json", json.dumps(state, indent=2) + "\n")


def archive(commit, paths, destination):
    output = destination.parent / (destination.name + ".tar")
    with output.open("wb") as stream:
        run(["git", "archive", commit, *paths], cwd=ROOT, stdout=stream)
    destination.mkdir()
    with tarfile.open(output) as archive_file:
        archive_file.extractall(destination, filter="data")
    output.unlink()


def request(url, method="GET", data=None, headers=None):
    body = None if data is None else json.dumps(data).encode()
    headers = dict(headers or {})
    if data is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    # Ignore host proxy settings: all runtime requests must stay on loopback.
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        response = client.open(req, timeout=3)
    except urllib.error.HTTPError as exc:
        response = exc
    with response:
        return response.status, response.read(), dict(response.headers)


def wait_http(url, wanted=200):
    for _ in range(120):
        try:
            if request(url)[0] == wanted:
                return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(1)
    raise RuntimeError(f"readiness failed for loopback endpoint {url}")


def container(directory, state, kind, image, mappings, environment=None):
    name = state["resource_prefix"] + "-" + kind
    arguments = ["docker", "run", "-d", "--name", name, "--label", "quizopia.owner=" + state["resource_prefix"]]
    if environment:
        env_path = directory / (kind + ".env")
        private_write(env_path, "\n".join(key + "=" + value for key, value in environment.items()) + "\n")
        arguments += ["--env-file", str(env_path)]
    for mapping in mappings:
        arguments += ["-p", mapping]
    arguments.append(image)
    run(arguments, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    state["containers"].append(name)
    state_write(directory, state)
    return name


def java_process(directory, state, name, jar, environment):
    log = (directory / (name + ".log")).open("wb")
    process = subprocess.Popen(
        ["java", "-Xmx256m", "-XX:ActiveProcessorCount=2", "-jar", str(jar)],
        env={**os.environ, **environment}, cwd=directory, stdout=log, stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    log.close()
    state["processes"].append({"pid": process.pid, "jar": str(jar)})
    state["artifacts"][name] = {"sha256": hashlib.sha256(jar.read_bytes()).hexdigest()}
    state_write(directory, state)


def stop_owned_browser(directory, group):
    """Stop only groups with a member in this launcher's private frontend archive.

    Chromium may create another process group. The private working-directory
    marker also finds detached descendants after the pnpm leader has exited.
    Never signal the caller's group or a group whose ownership cannot be verified.
    """
    frontend = (directory / "frontend-source" / "frontend").resolve()
    caller_group = os.getpgrp()
    for attempt in range(30):
        owned = {}
        for entry in Path("/proc").iterdir():
            if not entry.name.isdecimal():
                continue
            try:
                pid = int(entry.name)
                if (entry / "cwd").resolve() != frontend:
                    continue
                process_group = os.getpgid(pid)
                if process_group == caller_group:
                    continue
                owned.setdefault(process_group, []).append(pid)
            except (OSError, ProcessLookupError):
                continue
        if not owned:
            return
        for process_group, members in owned.items():
            # The original session group or an owned detached group leader.
            if process_group != group and process_group not in members:
                continue
            try:
                os.killpg(process_group, signal.SIGTERM if attempt < 10 else signal.SIGKILL)
            except ProcessLookupError:
                pass
        time.sleep(0.1)


def down(directory):
    directory = directory.resolve()
    state = json.loads((directory / "state.json").read_text())
    if state.get("browser_group") is not None:
        stop_owned_browser(directory, state["browser_group"])
    for process in state["processes"]:
        path = Path("/proc") / str(process["pid"]) / "cmdline"
        if path.exists() and process["jar"].encode() in path.read_bytes().split(b"\0"):
            os.kill(process["pid"], signal.SIGTERM)
    for name in reversed(state["containers"]):
        result = subprocess.run(["docker", "inspect", "--format", '{{ index .Config.Labels "quizopia.owner" }}', name], capture_output=True, text=True)
        if result.returncode == 0 and result.stdout.strip() == state["resource_prefix"]:
            run(["docker", "rm", "-f", "-v", name], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    state["stopped_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    state_write(directory, state)
    print("Owned runtime stopped; private evidence retained at", directory)


def smoke(directory):
    """Bounded synthetic authority checks, separate from the real browser suite."""
    state = json.loads((directory / "state.json").read_text())
    endpoints = state["endpoints"]
    gateway, mailpit, identity = (endpoints[name] for name in ["gateway", "mailpit", "identity"])
    checks = []

    def check(name, actual, expected):
        if actual not in expected:
            raise RuntimeError("Synthetic smoke assertion failed: " + name)
        checks.append({"check": name, "status": actual})

    username = "smoke" + secrets.token_hex(6)
    email = username + "@gmail.com"
    password = "Smoke-" + secrets.token_urlsafe(20) + "-A1!"
    check("registration", request(gateway + "/api/auth/register", "POST", {"username": username, "email": email, "password": password})[0], {202})
    check("otp-request", request(gateway + "/api/auth/email-verification/request", "POST", {"username": username})[0], {202})
    code = None
    for _ in range(60):
        messages = json.loads(request(mailpit + "/api/v1/messages")[1])["messages"]
        message = next((item for item in messages if any(recipient["Address"] == email for recipient in item["To"])), None)
        if message:
            import re
            detail = json.loads(request(mailpit + "/api/v1/message/" + message["ID"])[1])
            match = re.search(r"six-digit code:\s*(\d{6})", detail["Text"], re.I)
            if match:
                code = match.group(1)
                break
        time.sleep(0.5)
    if not code:
        raise RuntimeError("Synthetic verification email not received")
    check("otp-confirmation", request(gateway + "/api/auth/email-verification/confirm", "POST", {"username": username, "otp": code})[0], {204})
    status, body, _ = request(gateway + "/api/auth/login", "POST", {"identifier": username, "password": password})
    check("login", status, {200})
    user = json.loads(body)["accessToken"]
    claims = json.loads(base64.urlsafe_b64decode(user.split(".")[1] + "=="))
    check("isolated-user-token-ttl-seconds", claims["exp"] - claims["iat"], {5})
    check("authoritative-me", request(gateway + "/api/auth/me", headers={"Authorization": "Bearer " + user})[0], {200})
    process = next(item for item in state["processes"] if item["jar"].endswith("quizopia-gateway-0.1.0-SNAPSHOT.jar"))
    if process["jar"].encode() not in (Path("/proc") / str(process["pid"]) / "cmdline").read_bytes().split(b"\0"):
        raise RuntimeError("Recorded Gateway process ownership does not match")
    environment = dict(item.split(b"=", 1) for item in (Path("/proc") / str(process["pid"]) / "environ").read_bytes().split(b"\0") if b"=" in item)
    secret = environment[b"USER_ADMISSION_CLIENT_SECRET"].decode()
    basic = base64.b64encode(("gateway:" + secret).encode()).decode()
    form = urllib.parse.urlencode({"grant_type": "client_credentials", "scope": "identity.user-admission"}).encode()
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    oauth = urllib.request.Request(identity + "/oauth2/token", data=form,
                                   headers={"Authorization": "Basic " + basic, "Content-Type": "application/x-www-form-urlencoded"})
    with client.open(oauth, timeout=3) as response:
        service = json.load(response)["access_token"]
        check("exact-gateway-service-client-and-scope", response.status, {200})
    admission_headers = {"Authorization": "Bearer " + service, "X-Quizopia-User-Evidence": user}
    admission_url = identity + "/internal/security/user-admissions"
    for _ in range(30):
        status, body, headers = request(admission_url, "POST", headers=admission_headers)
        if status == 200:
            break
        time.sleep(0.25)
    check("primary-redis-equality-admission", status, {200})
    check("private-response-no-store", headers.get("Cache-Control") == "no-store", {True})
    check("user-cannot-impersonate-private-service", request(admission_url, "POST", headers={"Authorization": "Bearer " + user, "X-Quizopia-User-Evidence": user})[0], {401, 403})
    check("gateway-does-not-expose-private-route", request(gateway + "/internal/security/user-admissions", "POST", headers={"Authorization": "Bearer " + user})[0], {401, 403, 404})
    redis_name = next(name for name in state["containers"] if name.endswith("-redis"))
    label = subprocess.check_output(["docker", "inspect", "--format", '{{ index .Config.Labels "quizopia.owner" }}', redis_name], text=True).strip()
    if label != state["resource_prefix"]:
        raise RuntimeError("Redis ownership label mismatch")
    try:
        run(["docker", "stop", "--time", "1", redis_name], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        status, body, headers = request(admission_url, "POST", headers=admission_headers)
        check("redis-outage-fail-closed", status, {503})
        check("redis-outage-sanitized-code", json.loads(body) == {"code": "AUTHORITY_UNAVAILABLE"}, {True})
        check("redis-outage-no-store", headers.get("Cache-Control") == "no-store", {True})
    finally:
        run(["docker", "start", redis_name], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    for _ in range(30):
        status = request(admission_url, "POST", headers=admission_headers)[0]
        if status == 200:
            break
        time.sleep(0.25)
    check("restored-authority-admission", status, {200})
    for origin, allowed in [("http://localhost:3000", True), ("http://localhost:3100", False)]:
        status, _, headers = request(gateway + "/api/auth/login", "OPTIONS", headers={"Origin": origin, "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "content-type"})
        check("trusted-origin" if allowed else "untrusted-origin", status, {200} if allowed else {403})
    private_write(directory / "smoke-results.json", json.dumps(checks, indent=2) + "\n")
    print("PASS", len(checks), "synthetic backend smoke assertions; no browser outage or production acceptance claim")


def up(run_browser):
    import bcrypt

    os.umask(0o077)
    for port in [8080, 8025, 3000]:
        available("::1", port)  # Never replace an existing listener.
    directory = Path(tempfile.mkdtemp(prefix="quizopia-ld05-"))
    state = {"backend_sha": BACKEND, "frontend_sha": FRONTEND, "resource_prefix": "quizopia-ld05-" + secrets.token_hex(5),
             "containers": [], "processes": [], "artifacts": {}, "started_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
    state_write(directory, state)
    print("Private runtime/evidence directory:", directory, flush=True)
    try:
        snapshot = directory / "backend"
        archive(BACKEND, ["gateway", "services/identity-service"], snapshot)
        build_env = {**os.environ, "MAVEN_USER_HOME": str(Path.home() / ".local/share/quizopia-dev/maven"),
                     "MAVEN_OPTS": "-Xmx256m -XX:ActiveProcessorCount=2"}
        for module in ["services/identity-service", "gateway"]:
            with (directory / (module.split("/")[-1] + "-build.log")).open("wb") as log:
                run(["bash", str(snapshot / module / "mvnw"), "-B", "-f", str(snapshot / module / "pom.xml"),
                     "-DskipTests", "package"], env=build_env, stdout=log, stderr=subprocess.STDOUT)
            print("Built exact snapshot:", module, BACKEND, flush=True)
        db_port, redis_port, smtp_port, identity_port = [available("127.0.0.1", 0) for _ in range(4)]
        db_password = secrets.token_urlsafe(32)
        postgres = container(directory, state, "postgres", "postgres:17-alpine", [f"127.0.0.1:{db_port}:5432"],
                             {"POSTGRES_DB": "identity_db", "POSTGRES_USER": "identity", "POSTGRES_PASSWORD": db_password})
        container(directory, state, "redis", "redis:8.2-alpine", [f"127.0.0.1:{redis_port}:6379"])
        container(directory, state, "mailpit", "axllent/mailpit:v1.27.8", [f"127.0.0.1:{smtp_port}:1025", "[::1]:8025:8025"])
        for _ in range(60):
            if subprocess.run(["docker", "exec", postgres, "pg_isready", "-U", "identity"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
                break
            time.sleep(1)
        keys = directory / "keys"
        keys.mkdir()
        run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(keys / "private.pem")], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        run(["openssl", "pkey", "-in", str(keys / "private.pem"), "-pubout", "-out", str(keys / "public.pem")], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        issuer = f"http://127.0.0.1:{identity_port}"
        common = {"SPRING_PROFILES_ACTIVE": "local", "REDIS_HOST": "127.0.0.1", "REDIS_PORT": str(redis_port),
                  "IDENTITY_ISSUER": issuer, "IDENTITY_JWKS_URI": issuer + "/oauth2/jwks", "GATEWAY_ALLOWED_ORIGINS": "http://localhost:3000"}
        identity_env = {**common, "SERVER_PORT": str(identity_port), "SERVER_ADDRESS": "127.0.0.1",
                        "DB_URL": f"jdbc:postgresql://127.0.0.1:{db_port}/identity_db", "DB_USERNAME": "identity", "DB_PASSWORD": db_password,
                        "MAIL_HOST": "127.0.0.1", "MAIL_PORT": str(smtp_port), "IDENTITY_AUTHORIZATION_SERVER_ENABLED": "true",
                        "IDENTITY_AUTHORIZATION_SERVER_USER_ACCESS_TOKEN_TTL": "PT5S", "IDENTITY_SIGNING_KEY_KID": state["resource_prefix"],
                        "IDENTITY_SIGNING_KEY_PRIVATE_KEY_PATH": str(keys / "private.pem"), "IDENTITY_SIGNING_KEY_PUBLIC_KEY_PATH": str(keys / "public.pem"),
                        "IDENTITY_EMAIL_OUTBOX_ACTIVE_KEY_VERSION": "v1", "QUIZOPIA_IDENTITY_EMAIL_OUTBOX_ENCRYPTION_KEYS_V1": base64.b64encode(secrets.token_bytes(32)).decode(),
                        "IDENTITY_EMAIL_OUTBOX_DISPATCHER_ENABLED": "true", "IDENTITY_EMAIL_OUTBOX_POLLING_INTERVAL": "PT1S",
                        "IDENTITY_USER_ADMISSION_ENABLED": "true", "IDENTITY_USER_ADMISSION_ALLOWED_CLIENTS": "gateway",
                        "IDENTITY_USER_ADMISSION_REQUIRE_SECURE_TRANSPORT": "false", "IDENTITY_AUTHORITY_RECONCILIATION_ENABLED": "true",
                        "IDENTITY_AUTHORITY_POLLING_INTERVAL": "PT1S"}
        java_process(directory, state, "identity", snapshot / "services/identity-service/target/quizopia-identity-0.1.0-SNAPSHOT.jar", identity_env)
        wait_http(issuer + "/oauth2/jwks")
        client_secret = secrets.token_urlsafe(32)
        client_hash = "{bcrypt}" + bcrypt.hashpw(client_secret.encode(), bcrypt.gensalt()).decode()
        client_id = str(uuid.uuid4())
        sql = f"INSERT INTO oauth2_service_client VALUES ('{client_id}','gateway','{client_hash}',true,now(),now()); INSERT INTO oauth2_service_client_scope VALUES ('{client_id}','identity.user-admission');"
        run(["docker", "exec", "-i", postgres, "psql", "-U", "identity", "-d", "identity_db", "-v", "ON_ERROR_STOP=1"], input=sql.encode(), stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        gateway_env = {**common, "SERVER_PORT": "8080", "SERVER_ADDRESS": "::1", "IDENTITY_SERVICE_URL": issuer,
                       "USER_ADMISSION_ENABLED": "true", "USER_ADMISSION_IDENTITY_BASE_URL": issuer,
                       "USER_ADMISSION_CLIENT_ID": "gateway", "USER_ADMISSION_CLIENT_SECRET": client_secret,
                       "USER_ADMISSION_ALLOW_INSECURE_LOOPBACK": "true"}
        java_process(directory, state, "gateway", snapshot / "gateway/target/quizopia-gateway-0.1.0-SNAPSHOT.jar", gateway_env)
        wait_http("http://[::1]:8080/actuator/health")
        wait_http("http://[::1]:8025/api/v1/messages")
        state["endpoints"] = {"gateway": "http://[::1]:8080", "mailpit": "http://[::1]:8025", "identity": issuer}
        state["user_token_ttl"] = "PT5S"
        state_write(directory, state)
        print("PASS owned Gateway/Identity/PostgreSQL/Redis/Mailpit readiness; test USER TTL PT5S", flush=True)
        if run_browser:
            frontend_snapshot = directory / "frontend-source"
            archive(FRONTEND, ["frontend"], frontend_snapshot)
            frontend = frontend_snapshot / "frontend"
            for filename in ["package.json", "pnpm-lock.yaml"]:
                if (frontend / filename).read_bytes() != (ROOT / "frontend" / filename).read_bytes():
                    raise RuntimeError("Existing frontend dependency provenance differs; install the archived lockfile separately")
            (frontend / "node_modules").symlink_to(ROOT / "frontend/node_modules", target_is_directory=True)
            private_bin = directory / "bin"
            private_bin.mkdir()
            pnpm = private_bin / "pnpm"
            pnpm.write_text("#!/bin/sh\nexec corepack pnpm \"$@\"\n")
            pnpm.chmod(0o700)
            environment = {**os.environ, "PATH": str(private_bin) + os.pathsep + os.environ["PATH"], "LD05_REAL_EXECUTOR_HOST": "::1"}
            with (directory / "browser.log").open("wb") as log:
                browser = subprocess.Popen(["pnpm", "test:e2e:real:executor"], cwd=frontend,
                                           env=environment, stdout=log, stderr=subprocess.STDOUT,
                                           start_new_session=True)
                state["browser_group"] = browser.pid
                state_write(directory, state)
                try:
                    browser_exit_code = browser.wait(timeout=240)
                finally:
                    stop_owned_browser(directory, browser.pid)
                    browser.wait(timeout=5)
            state["browser_exit_code"] = browser_exit_code
            state_write(directory, state)
            print("Real PR #77 browser executor exit:", browser_exit_code, "(private browser.log)", flush=True)
            if browser_exit_code:
                raise RuntimeError("Browser executor failed; inspect private evidence, never publish credential-bearing artifacts")
        print("Runtime is owned and active. Stop only these resources with:", flush=True)
        print("python3 scripts/local/ld05-real-executor-runtime.py down", directory, flush=True)
    except Exception:
        down(directory)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("up")
    create.add_argument("--run-browser", action="store_true")
    stop = commands.add_parser("down")
    stop.add_argument("state_directory", type=Path)
    probe = commands.add_parser("smoke")
    probe.add_argument("state_directory", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "up":
            up(args.run_browser)
        elif args.command == "smoke":
            smoke(args.state_directory)
        else:
            down(args.state_directory)
    except Exception as exc:
        print("FAIL isolated runtime:", type(exc).__name__, "(details remain in private evidence directory)", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
