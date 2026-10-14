#!/usr/bin/env python3
"""Read-only release-register checks; evidence authenticity still requires human review.

Uses the repository's existing PyYAML installation. Never contacts an environment,
installs dependencies, deploys a service, or turns a structural check into approval.
"""

import argparse
from collections import Counter
from datetime import datetime, timezone
import ipaddress
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import urlparse

import yaml

ROOT = Path(__file__).resolve().parents[3]
BASELINE = "e615764613b661f8503de5fb5494fc7ea5224ba3"
MATRIX = "docs/architecture/w3-final-acceptance-matrix.md"
DEFAULT_REGISTER = "docs/development/w3-production-evidence-register.yaml"
UNASSIGNED = "UNASSIGNED — LEADER ASSIGNMENT REQUIRED"
INHERITED = {
    "BUILD-02": "NOT VERIFIED",
    "NOTICE-04": "NOT VERIFIED",
    "RECOVERY-05": "BLOCKED",
    "DB-05": "NOT VERIFIED",
    "DB-08": "BLOCKED",
    "TLS-02": "BLOCKED",
    "CAPACITY-04": "NOT VERIFIED",
    "OBSERVE-02": "BLOCKED",
    "PRIVACY-02": "BLOCKED",
    "DEPLOY-02": "NOT VERIFIED",
    "APPROVAL-01": "BLOCKED",
}
# This accepted mapping is also checked against the pinned historical Git object.
INHERITED_SEVERITIES = {
    "BUILD-02": "MEDIUM", "NOTICE-04": "HIGH", "RECOVERY-05": "HIGH",
    "DB-05": "HIGH", "DB-08": "HIGH", "TLS-02": "HIGH", "CAPACITY-04": "HIGH",
    "OBSERVE-02": "HIGH", "PRIVACY-02": "HIGH", "DEPLOY-02": "HIGH", "APPROVAL-01": "HIGH",
}
COORDINATION_ROLES = {"DEV1_BACKEND", "LEADER", "DEV2_FRONTEND", "UNASSIGNED"}
COORDINATION_DRI_ROLES = {
    "BUILD-02": "DEV1_BACKEND", "NOTICE-04": "LEADER", "RECOVERY-05": "LEADER",
    "DB-05": "DEV1_BACKEND", "DB-08": "LEADER", "TLS-02": "LEADER",
    "CAPACITY-04": "DEV1_BACKEND", "OBSERVE-02": "LEADER", "PRIVACY-02": "LEADER",
    "DEPLOY-02": "DEV1_BACKEND", "APPROVAL-01": "LEADER",
}
ROLE_ONLY_DRIS = {role.casefold() for role in COORDINATION_ROLES} | {
    "dev1", "dev 1", "dev1 backend", "dev 1 backend", "dev 1 — backend", "dev 1 - backend",
    "dev2", "dev 2", "dev2 frontend", "dev 2 frontend", "dev 2 — frontend", "dev 2 - frontend",
}
COUNTS = {"PASS": 46, "FAIL": 0, "BLOCKED": 6, "NOT VERIFIED": 5, "NOT APPLICABLE": 2}
STATUSES = set(COUNTS)
SHA = re.compile(r"[0-9a-f]{40}\Z")
DIGEST = re.compile(r"(?:sha256:)?[0-9a-f]{64}\Z")
PLACEHOLDER = re.compile(r"\b(?:TODO|TBD|placeholder|unassigned)\b", re.I)
ROLE_ONLY_REVIEWERS = {
    "leader", "security", "security reviewer", "independent security",
    "independent security reviewer", "independent_security", "gate approver",
    "gate_approver", "reviewer",
} | ROLE_ONLY_DRIS
DEPLOYED_GATES = set(INHERITED) - {"BUILD-02", "PRIVACY-02"}
REQUIRED_APPROVALS = {
    "BUILD-02": {"GATE_APPROVER"}, "NOTICE-04": {"INDEPENDENT_SECURITY"},
    "RECOVERY-05": {"GATE_APPROVER", "INDEPENDENT_SECURITY"}, "DB-05": {"GATE_APPROVER"},
    "DB-08": {"GATE_APPROVER", "INDEPENDENT_SECURITY"}, "TLS-02": {"GATE_APPROVER", "INDEPENDENT_SECURITY"},
    "CAPACITY-04": {"GATE_APPROVER"}, "OBSERVE-02": {"GATE_APPROVER", "INDEPENDENT_SECURITY"},
    "PRIVACY-02": {"LEADER", "INDEPENDENT_SECURITY"}, "DEPLOY-02": {"GATE_APPROVER", "INDEPENDENT_SECURITY"},
    "APPROVAL-01": {"LEADER", "INDEPENDENT_SECURITY"},
}
GATE_STRINGS = {
    "id", "inherited_status", "status", "objective", "severity", "proposed_owner",
    "dri", "executor", "independent_reviewer", "approval_authority", "target_environment",
    "remediation", "execution_authorization", "final_signoff", "blocker", "ownership_status",
}
GATE_LISTS = {"prerequisites", "procedure", "pass_criteria", "minimum_evidence", "dependencies", "revalidate_on"}
GATE_KEYS = GATE_STRINGS | GATE_LISTS | {"release_blocking", "evidence", "approvals"}
RECEIPT_STRINGS = {
    "release_candidate_sha", "procedure", "executed_at", "executor", "expected_result",
    "actual_result", "reference", "integrity_sha256", "limitations", "independent_review_reference",
}
APPROVAL_KEYS = {"kind", "authority", "reviewer", "decided_at", "release_candidate_sha", "reference", "decision"}
WAIVER_KEYS = {"decision_maker", "authority", "risk", "scope", "review_conditions", "reference", "release_candidate_sha", "decided_at"}


class UniqueKeyLoader(yaml.SafeLoader):
    """YAML duplicate keys are errors rather than silent overwrites."""


def unique_mapping(loader, node, deep=False):
    mapping = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        try:
            duplicate = key in mapping
        except TypeError as exc:
            raise ValueError("YAML mapping keys must be scalar") from exc
        if duplicate:
            raise ValueError(f"duplicate YAML key: {key}")
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


UniqueKeyLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping)


def load_register(path):
    with Path(path).open(encoding="utf-8") as source:
        return yaml.load(source, Loader=UniqueKeyLoader)


def matrix_rows(content):
    rows = {}
    for line in content.splitlines():
        cells = [cell.strip() for cell in line.split("|")]
        if len(cells) > 6 and cells[5] in STATUSES:
            gate_id = cells[1].split()[0]
            if gate_id in rows:
                raise ValueError(f"duplicate inherited matrix ID: {gate_id}")
            rows[gate_id] = cells[5]
    return rows


def matrix_severities(content):
    """Read the severity column without changing the historical status parser."""
    severities = {}
    for line in content.splitlines():
        cells = [cell.strip() for cell in line.split("|")]
        if len(cells) > 5 and cells[5] in STATUSES:
            if len(cells) != 10 or not cells[1]:
                raise ValueError("malformed historical gate row")
            gate_id = cells[1].split()[0]
            severity, separator, owner = cells[6].partition("/")
            severity = severity.strip()
            if severity not in {"MEDIUM", "HIGH", "BLOCKER"} or not separator or not owner.strip():
                raise ValueError(f"malformed historical severity: {gate_id}")
            if gate_id in severities:
                raise ValueError(f"duplicate historical severity: {gate_id}")
            severities[gate_id] = severity
    return severities


def normalized_identity(value):
    """Compare labels deterministically; this cannot prove human identity."""
    return " ".join(value.split()).casefold() if isinstance(value, str) else None


def validate_register(document, root=ROOT, verify_baseline=True):
    """Return structural errors, without resolving URLs or asserting evidence truth.

    verify_baseline=False is only for isolated synthetic unit fixtures. The CLI
    always verifies the matrix against the pinned merged Git object.
    """
    errors = []

    def error(where, message):
        errors.append(f"{where}: {message}")

    def mapping(value, required, optional, where):
        if not isinstance(value, dict):
            error(where, "must be a mapping")
            return False
        for key in sorted(required - value.keys()):
            error(where, f"missing {key}")
        for key in sorted(value.keys() - required - optional, key=str):
            error(where, f"unknown field {key}")
        return True

    def string(value, where, verified=False):
        if not isinstance(value, str) or not value.strip():
            error(where, "must be a non-empty string")
            return False
        if verified and PLACEHOLDER.search(value):
            error(where, "verified value contains an unassigned/placeholder marker")
            return False
        if verified and re.fullmatch(r"unknown|pending|not assigned|none|n/?a|unconfirmed", value.strip(), re.I):
            error(where, "verified identity/value cannot be an unknown/pending marker")
            return False
        return True

    def strings(value, where, allow_empty=False, verified=False):
        if not isinstance(value, list) or (not value and not allow_empty):
            error(where, "must be a list" + ("" if allow_empty else " with at least one entry"))
            return False
        for index, item in enumerate(value):
            string(item, f"{where}[{index}]", verified=verified)
        return True

    def identity(value, where):
        if string(value, where, verified=True) and re.fullmatch(
                r"unknown|pending|not assigned|none|n/?a|unconfirmed", value.strip(), re.I):
            error(where, "verified identity cannot be an unknown/pending assignment marker")

    def timestamp(value, where):
        if not string(value, where):
            return
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
            if parsed.tzinfo is None or parsed.utcoffset() is None:
                raise ValueError("timezone required")
            if parsed > datetime.now(timezone.utc):
                raise ValueError("execution/decision cannot be in the future")
        except ValueError:
            error(where, "must be an actual, timezone-qualified ISO timestamp, not a future date")

    def digest(value, where):
        if not isinstance(value, str) or not DIGEST.fullmatch(value):
            error(where, "must be a SHA-256 digest")

    def reference(value, where):
        if not string(value, where, verified=True):
            return
        parsed = urlparse(value)
        if parsed.scheme:
            host = parsed.hostname or ""
            try:
                ipaddress.ip_address(host)
                literal_address = True
            except ValueError:
                literal_address = False
            if (parsed.scheme != "https" or not host or parsed.username or parsed.password
                    or parsed.query or parsed.fragment
                    or host in {"localhost", "example.com", "example.org", "example.net"}
                    or host.endswith((".example.com", ".example.org", ".example.net"))
                    or host.endswith((".invalid", ".example", ".test", ".localhost"))
                    or literal_address):
                error(where, "must be a real HTTPS reference without secrets, query, fragment, or placeholder host")
        else:
            candidate = (root / value).resolve()
            if Path(value).is_absolute() or not candidate.is_relative_to(root.resolve()) or not candidate.is_file():
                error(where, "repository evidence reference must resolve to an existing in-repository file")

    required_top = {"schema_version", "baseline", "release_candidate_sha", "verdict", "gates"}
    optional_top = {"observations", "evidence_contract", "stages", "recorded_on", "scope", "ownership_status"}
    if not mapping(document, required_top, optional_top, "register"):
        return errors
    schema_version = document.get("schema_version")
    if type(schema_version) is not int or schema_version not in (1, 2):
        error("schema_version", "must be supported integer 1 or 2")
    # Version 1 remains an unambiguous historical contract. Planning-role
    # metadata is required only in version 2 and is unknown in version 1.
    gate_keys = GATE_KEYS | {"coordination_dri_role"} if schema_version == 2 else GATE_KEYS
    baseline = document.get("baseline")
    if mapping(baseline, {"commit", "matrix", "counts"}, set(), "baseline"):
        if baseline.get("commit") != BASELINE or baseline.get("matrix") != MATRIX:
            error("baseline", "must retain the exact PR #84 merged baseline and matrix path")
        counts = baseline.get("counts")
        if (not isinstance(counts, dict) or counts != COUNTS
                or any(type(value) is not int for value in counts.values())):
            error("baseline.counts", "must retain 46 PASS, 0 FAIL, 6 BLOCKED, 5 NOT VERIFIED, 2 NOT APPLICABLE")
    if verify_baseline:
        try:
            pinned = subprocess.check_output(["git", "show", f"{BASELINE}:{MATRIX}"], cwd=root, stderr=subprocess.DEVNULL)
            current = (root / MATRIX).read_bytes()
            rows = matrix_rows(pinned.decode("utf-8"))
            severities = matrix_severities(pinned.decode("utf-8"))
            if current != pinned:
                error("baseline.matrix", "historical 59-gate matrix differs from the pinned merged version")
            if len(rows) != 59 or dict(Counter(rows.values())) != {key: value for key, value in COUNTS.items() if value}:
                error("baseline.matrix", "pinned matrix row/count mismatch")
            if {key: value for key, value in rows.items() if value in {"BLOCKED", "NOT VERIFIED"}} != INHERITED:
                error("baseline.matrix", "pinned inherited open-gate set/status mismatch")
            if set(severities) != set(rows) or {key: severities.get(key) for key in INHERITED} != INHERITED_SEVERITIES:
                error("baseline.matrix", "inherited severity authority mismatch with pinned historical matrix")
        except (OSError, ValueError, subprocess.CalledProcessError) as exc:
            error("baseline.matrix", f"cannot verify pinned matrix ({type(exc).__name__})")
    candidate = document.get("release_candidate_sha")
    if candidate is not None and (not isinstance(candidate, str) or not SHA.fullmatch(candidate)):
        error("release_candidate_sha", "must be null or an exact lowercase 40-character Git SHA")
    for key, expected_type in {"observations": list, "evidence_contract": dict, "stages": dict, "recorded_on": str, "scope": str, "ownership_status": str}.items():
        if key in document and not isinstance(document[key], expected_type):
            error(key, f"must be {expected_type.__name__}")
    if "ownership_status" in document and document["ownership_status"] not in ("PROPOSED", "ASSIGNED"):
        error("ownership_status", "must be PROPOSED or ASSIGNED")

    gates = document.get("gates")
    if not isinstance(gates, list):
        error("gates", "must be a list of exactly the eleven inherited open gates")
        return errors
    ids = [gate.get("id") for gate in gates if isinstance(gate, dict) and isinstance(gate.get("id"), str)]
    if len(gates) != 11 or len(ids) != 11 or set(ids) != set(INHERITED):
        error("gates", "must contain exactly the eleven inherited open gate IDs, with no deletion/addition")
    if len(ids) != len(set(ids)):
        error("gates", "duplicate gate IDs")
    by_id = {gate["id"]: gate for gate in gates if isinstance(gate, dict) and isinstance(gate.get("id"), str)}
    if schema_version == 2:
        coordination_counts = Counter(gate.get("coordination_dri_role") for gate in gates
                                      if isinstance(gate, dict) and isinstance(gate.get("coordination_dri_role"), str))
        if coordination_counts != {"DEV1_BACKEND": 4, "LEADER": 7}:
            error("gates", "coordination role counts must remain 4 DEV1_BACKEND, 7 LEADER, 0 DEV2_FRONTEND/UNASSIGNED")

    for index, gate in enumerate(gates):
        where = f"gates[{index}]"
        if not mapping(gate, gate_keys, {"waiver"}, where):
            continue
        gate_id = gate.get("id")
        if isinstance(gate_id, str):
            where = gate_id
        for key in sorted(GATE_STRINGS):
            string(gate.get(key), f"{where}.{key}")
        for key in sorted(GATE_LISTS):
            strings(gate.get(key), f"{where}.{key}", allow_empty=key == "dependencies")
        if not isinstance(gate_id, str) or gate_id not in INHERITED:
            continue
        if schema_version == 2:
            role = gate.get("coordination_dri_role")
            if not isinstance(role, str) or role not in COORDINATION_ROLES:
                error(where, "coordination_dri_role must be a controlled coordination role")
            elif role != COORDINATION_DRI_ROLES[gate_id]:
                error(where, f"selected coordination role must remain {COORDINATION_DRI_ROLES[gate_id]}")
        if gate.get("inherited_status") != INHERITED[gate_id]:
            error(where, "inherited status differs from the authoritative matrix")
        status = gate.get("status")
        if not isinstance(status, str) or status not in STATUSES:
            error(where, "invalid status")
        if gate.get("severity") != INHERITED_SEVERITIES[gate_id]:
            error(where, f"inherited severity must remain {INHERITED_SEVERITIES[gate_id]}")
        if gate.get("release_blocking") is not True:
            error(where, "all inherited open gates must retain release_blocking: true")
        ownership = gate.get("ownership_status")
        if not isinstance(ownership, str) or ownership not in {"PROPOSED", "ASSIGNED"}:
            error(where, "ownership_status must be PROPOSED or ASSIGNED")
        if ownership == "PROPOSED" and gate.get("dri") != UNASSIGNED:
            error(where, "proposed ownership must use the exact UNASSIGNED DRI marker")
        if ownership == "ASSIGNED":
            identity(gate.get("dri"), f"{where}.dri")
            if normalized_identity(gate.get("dri")) in ROLE_ONLY_DRIS:
                error(where, "DRI must identify an individual, not a coordination role label")
        dependencies = gate.get("dependencies")
        if isinstance(dependencies, list):
            if any(not isinstance(dep, str) or dep not in INHERITED or dep == gate_id for dep in dependencies):
                error(where, "dependencies must reference other inherited gate IDs")
            elif len(dependencies) != len(set(dependencies)):
                error(where, "duplicate dependency")
            if gate_id == "APPROVAL-01" and set(dep for dep in dependencies if isinstance(dep, str)) != set(INHERITED) - {"APPROVAL-01"}:
                error(where, "final release reconciliation must retain all ten other gate dependencies")
        closed = isinstance(status, str) and status in {"PASS", "NOT APPLICABLE"}
        if closed and (candidate is None or ownership != "ASSIGNED"):
            error(where, "closure requires an exact release candidate SHA and assigned DRI")
        if status == "NOT APPLICABLE" and gate_id != "NOTICE-04":
            error(where, "only NOTICE-04 supports the explicitly authorized soak waiver")
        if status != "NOT APPLICABLE" and "waiver" in gate:
            error(where, "waiver is only valid for NOTICE-04 NOT APPLICABLE")
        evidence = gate.get("evidence")
        approvals = gate.get("approvals")
        if not isinstance(evidence, list) or not isinstance(approvals, list):
            error(where, "evidence and approvals must be lists")
            continue
        if status == "PASS" and not evidence:
            error(where, "PASS requires actual execution evidence")
        if closed and not approvals:
            error(where, "closed status requires recorded approval")
        for receipt_index, receipt in enumerate(evidence):
            receipt_where = f"{where}.evidence[{receipt_index}]"
            if not mapping(receipt, RECEIPT_STRINGS | {"environment", "tools"}, {"artifact_digest", "configuration_fingerprint"}, receipt_where):
                continue
            for key in sorted(RECEIPT_STRINGS):
                string(receipt.get(key), f"{receipt_where}.{key}", verified=key not in {"limitations"})
            identity(receipt.get("executor"), f"{receipt_where}.executor")
            if receipt.get("release_candidate_sha") != candidate or candidate is None:
                error(receipt_where, "evidence SHA must bind to the exact selected release candidate")
            timestamp(receipt.get("executed_at"), f"{receipt_where}.executed_at")
            reference(receipt.get("reference"), f"{receipt_where}.reference")
            reference(receipt.get("independent_review_reference"), f"{receipt_where}.independent_review_reference")
            digest(receipt.get("integrity_sha256"), f"{receipt_where}.integrity_sha256")
            strings(receipt.get("tools"), f"{receipt_where}.tools", verified=True)
            environment = receipt.get("environment")
            if mapping(environment, {"name", "classification"}, set(), f"{receipt_where}.environment"):
                for key in ["name", "classification"]:
                    string(environment.get(key), f"{receipt_where}.environment.{key}", verified=True)
            if gate_id in DEPLOYED_GATES or "artifact_digest" in receipt or "configuration_fingerprint" in receipt:
                for key in ["artifact_digest", "configuration_fingerprint"]:
                    digest(receipt.get(key), f"{receipt_where}.{key}")
        kinds = set()
        authority_reviewers = {"LEADER": set(), "INDEPENDENT_SECURITY": set()}
        for approval_index, approval in enumerate(approvals):
            approval_where = f"{where}.approvals[{approval_index}]"
            if not mapping(approval, APPROVAL_KEYS, {"artifact_digest", "configuration_fingerprint"}, approval_where):
                continue
            for key in sorted(APPROVAL_KEYS):
                string(approval.get(key), f"{approval_where}.{key}", verified=True)
            identity(approval.get("reviewer"), f"{approval_where}.reviewer")
            kind = approval.get("kind")
            if not isinstance(kind, str) or kind not in {"INDEPENDENT_SECURITY", "LEADER", "GATE_APPROVER"}:
                error(approval_where, "invalid approval kind")
            elif isinstance(kind, str):
                kinds.add(kind)
                if kind in authority_reviewers and isinstance(approval.get("reviewer"), str):
                    authority_reviewers[kind].add(normalized_identity(approval["reviewer"]))
            if approval.get("decision") != "APPROVED":
                error(approval_where, "approval decision must be APPROVED")
            if approval.get("release_candidate_sha") != candidate or candidate is None:
                error(approval_where, "approval SHA must bind to the exact selected release candidate")
            timestamp(approval.get("decided_at"), f"{approval_where}.decided_at")
            reference(approval.get("reference"), f"{approval_where}.reference")
            reviewer = normalized_identity(approval.get("reviewer"))
            if reviewer in ROLE_ONLY_REVIEWERS:
                error(approval_where, "approval reviewer must identify an individual, not a role-only label")
            executors = {normalized_identity(receipt["executor"]) for receipt in evidence if isinstance(receipt, dict) and isinstance(receipt.get("executor"), str)}
            if kind in ("INDEPENDENT_SECURITY", "GATE_APPROVER") and reviewer is not None and (reviewer == normalized_identity(gate.get("dri")) or reviewer in executors):
                error(approval_where, "reviewer must be independent of the DRI and evidence executor")
            if status == "PASS" and gate_id in DEPLOYED_GATES:
                for key in ["artifact_digest", "configuration_fingerprint"]:
                    digest(approval.get(key), f"{approval_where}.{key}")
                approved_binding = (approval.get("artifact_digest"), approval.get("configuration_fingerprint"))
                receipt_bindings = [(receipt.get("artifact_digest"), receipt.get("configuration_fingerprint")) for receipt in evidence if isinstance(receipt, dict)]
                if approved_binding not in receipt_bindings:
                    error(approval_where, "approval artifact/configuration binding must match tested deployment evidence")
        if closed and not REQUIRED_APPROVALS[gate_id] <= kinds:
            error(where, "required approval kinds: " + ", ".join(sorted(REQUIRED_APPROVALS[gate_id])))
        if ((gate_id == "APPROVAL-01" and status == "PASS")
                or (gate_id == "NOTICE-04" and status == "NOT APPLICABLE")):
            if authority_reviewers["LEADER"] & authority_reviewers["INDEPENDENT_SECURITY"]:
                error(where, "Leader and independent Security reviewers must be distinct individuals")
        if status == "NOT APPLICABLE":
            waiver = gate.get("waiver")
            if mapping(waiver, WAIVER_KEYS, set(), f"{where}.waiver"):
                for key in sorted(WAIVER_KEYS):
                    string(waiver.get(key), f"{where}.waiver.{key}", verified=True)
                identity(waiver.get("decision_maker"), f"{where}.waiver.decision_maker")
                timestamp(waiver.get("decided_at"), f"{where}.waiver.decided_at")
                reference(waiver.get("reference"), f"{where}.waiver.reference")
                if waiver.get("release_candidate_sha") != candidate or candidate is None:
                    error(where, "waiver SHA must bind to the exact selected release candidate")
            if not {"LEADER", "INDEPENDENT_SECURITY"} <= kinds:
                error(where, "soak waiver requires explicit Leader and independent-security approvals")
        # A formally authorized NOTICE-04 waiver is a governance decision, not
        # execution of the soak; other technical gates still independently block.
        if status == "PASS" and isinstance(dependencies, list):
            for dependency in dependencies:
                dependency_status = by_id.get(dependency, {}).get("status") if isinstance(dependency, str) else None
                if not isinstance(dependency_status, str) or dependency_status not in {"PASS", "NOT APPLICABLE"}:
                    error(where, f"closed gate has open dependency {dependency}")

    visiting, visited = set(), set()

    def visit(gate_id):
        if gate_id in visiting:
            error("dependencies", "cycle detected")
            return
        if gate_id in visited:
            return
        visiting.add(gate_id)
        dependencies = by_id[gate_id].get("dependencies", [])
        if isinstance(dependencies, list):
            for dependency in dependencies:
                if isinstance(dependency, str) and dependency in by_id:
                    visit(dependency)
        visiting.remove(gate_id)
        visited.add(gate_id)

    for gate_id in by_id:
        visit(gate_id)
    # This workstream collects acceptance evidence; it cannot declare production GO.
    if document.get("verdict") != "PRODUCTION NO-GO":
        error("verdict", "this coordination register must preserve PRODUCTION NO-GO; release authorization is external")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("register", nargs="?", default=DEFAULT_REGISTER)
    args = parser.parse_args()
    try:
        register_path = (ROOT / args.register).resolve()
        document = load_register(register_path)
        errors = validate_register(document)
        if register_path == (ROOT / DEFAULT_REGISTER).resolve() and (
                not isinstance(document, dict) or document.get("schema_version") != 2):
            errors.append("current release register: schema 2 coordination contract is required; schema 1 is only supported for archives")
    except (OSError, ValueError, yaml.YAMLError) as exc:
        print(f"FAIL release register: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1
    if errors:
        for message in errors:
            print("FAIL", message, file=sys.stderr)
        return 1
    counts = dict(sorted(Counter(gate["status"] for gate in document["gates"]).items()))
    print(f"PASS release-register structure, pinned 59-gate history, exact eleven-gate overlay: {counts}")
    if document["schema_version"] == 2:
        print("PASS schema 2 coordination mapping: 4 DEV1_BACKEND, 7 LEADER, 0 DEV2_FRONTEND; human identity/approval requirements remain separate.")
    print("Production NO-GO preserved. Structural validation does not prove artifact authenticity, independent review, or release approval.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
