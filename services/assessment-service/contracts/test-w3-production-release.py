#!/usr/bin/env python3
"""Synthetic in-memory validator fixtures; no runtime gate is executed or closed."""

from copy import deepcopy
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location(
    "release_validator", Path(__file__).with_name("validate-w3-production-release.py")
)
validator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validator)


def fixture():
    """Test-only open register, deliberately unrelated to execution evidence."""
    gates = []
    for gate_id, status in validator.INHERITED.items():
        gate = {key: "Required operator instruction" for key in validator.GATE_STRINGS}
        gate.update({key: ["Required condition"] for key in validator.GATE_LISTS})
        gate.update({
            "id": gate_id, "inherited_status": status, "status": status,
            "severity": "HIGH", "proposed_owner": "Accountable role",
            "dri": validator.UNASSIGNED, "ownership_status": "PROPOSED",
            "release_blocking": True, "dependencies": [], "evidence": [], "approvals": [],
        })
        if gate_id == "APPROVAL-01":
            gate["dependencies"] = sorted(set(validator.INHERITED) - {"APPROVAL-01"})
        gates.append(gate)
    return {
        "schema_version": 1,
        "baseline": {"commit": validator.BASELINE, "matrix": validator.MATRIX, "counts": deepcopy(validator.COUNTS)},
        "release_candidate_sha": None, "verdict": "PRODUCTION NO-GO", "gates": gates,
    }


def approval(kind="GATE_APPROVER"):
    return {
        "kind": kind, "authority": "Authorized gate authority", "reviewer": "Assigned reviewer",
        "decided_at": "2026-01-01T12:00:00Z", "release_candidate_sha": "a" * 40,
        "reference": "https://github.com/hhtuann/Quizopia_2.0/pull/84", "decision": "APPROVED",
        "artifact_digest": "c" * 64, "configuration_fingerprint": "d" * 64,
    }


def close_gate(document, gate_id="BUILD-02"):
    """Illustrative contract shape, never used to amend the real register."""
    document["release_candidate_sha"] = "a" * 40
    gate = next(item for item in document["gates"] if item["id"] == gate_id)
    gate.update({"status": "PASS", "ownership_status": "ASSIGNED", "dri": "Assigned operator"})
    gate["evidence"] = [{
        "release_candidate_sha": "a" * 40, "procedure": "Approved operator procedure",
        "executed_at": "2026-01-01T12:00:00Z", "environment": {"name": "Authorized environment", "classification": "CI"},
        "executor": "Assigned operator", "tools": ["Python 3.12"], "expected_result": "All required checks succeed",
        "actual_result": "All required checks succeeded", "reference": "https://github.com/hhtuann/Quizopia_2.0/pull/84",
        "integrity_sha256": "b" * 64, "limitations": "Structural fixture does not prove authenticity",
        "independent_review_reference": "https://github.com/hhtuann/Quizopia_2.0/pull/84",
        "artifact_digest": "c" * 64, "configuration_fingerprint": "d" * 64,
    }]
    gate["approvals"] = [approval()]
    return gate


class RegisterSafetyTests(unittest.TestCase):
    def setUp(self):
        self.document = fixture()

    def errors(self, document=None):
        return validator.validate_register(document if document is not None else self.document, verify_baseline=False)

    def assertRejected(self, fragment):
        errors = self.errors()
        self.assertTrue(any(fragment in message for message in errors), errors)

    def test_open_inherited_register_valid_without_execution_claims(self):
        self.assertEqual([], self.errors())

    def test_missing_or_additional_gate_rejected(self):
        for change in [lambda gates: gates.pop(), lambda gates: gates.append(deepcopy(gates[0]))]:
            with self.subTest(change=change):
                self.document = fixture()
                change(self.document["gates"])
                self.assertRejected("exactly the eleven")

    def test_duplicate_or_renamed_id_rejected(self):
        self.document["gates"][1]["id"] = "BUILD-02"
        self.assertRejected("duplicate gate IDs")

    def test_tampered_inherited_status_or_counts_rejected(self):
        self.document["gates"][0]["inherited_status"] = "PASS"
        self.document["baseline"]["counts"]["PASS"] = 47
        self.assertRejected("inherited status")
        self.assertRejected("must retain 46 PASS")

    def test_missing_empty_criteria_and_roles_rejected(self):
        for key, value in [("pass_criteria", []), ("proposed_owner", ""), ("independent_reviewer", "")]:
            with self.subTest(key=key):
                self.document = fixture()
                self.document["gates"][0][key] = value
                self.assertRejected(key)

    def test_wrong_type_fail_closed_without_exception(self):
        for key in validator.GATE_KEYS:
            with self.subTest(key=key):
                self.document = fixture()
                self.document["gates"][0][key] = {"malformed": []}
                self.assertTrue(self.errors())

    def test_unknown_fields_and_boolean_schema_rejected(self):
        self.document["schema_version"] = True
        self.document["gates"][0]["unrecognized_override"] = True
        self.assertRejected("schema_version")
        self.assertRejected("unknown field")

    def test_pass_without_rc_assignment_evidence_or_approval_rejected(self):
        self.document["gates"][0]["status"] = "PASS"
        self.assertRejected("exact release candidate")
        self.assertRejected("actual execution evidence")
        self.assertRejected("recorded approval")

    def test_contract_complete_pass_fixture_is_structurally_valid_only(self):
        close_gate(self.document)
        self.assertEqual([], self.errors())

    def test_pass_without_independent_review_reference_rejected(self):
        gate = close_gate(self.document)
        del gate["evidence"][0]["independent_review_reference"]
        self.assertRejected("independent_review_reference")

    def test_wrong_rc_binding_rejected_for_receipt_and_approval(self):
        gate = close_gate(self.document)
        gate["evidence"][0]["release_candidate_sha"] = "e" * 40
        gate["approvals"][0]["release_candidate_sha"] = "e" * 40
        self.assertRejected("evidence SHA")
        self.assertRejected("approval SHA")

    def test_placeholder_or_credential_or_external_path_reference_rejected(self):
        for ref in ["https://example.com/proof", "https://nested.example.com/proof", "https://evidence.invalid/proof", "https://localhost/proof",
                    "https://[::1]/proof", "https://127.0.0.1/proof",
                    "https://github.com/proof?token=private", "https://user:secret@github.com/proof",
                    "https://github.com/TODO/proof", "../../private-proof.txt", "/etc/passwd", "missing-evidence.json"]:
            with self.subTest(reference=ref):
                self.document = fixture()
                close_gate(self.document)["evidence"][0]["reference"] = ref
                self.assertRejected("reference")

    def test_wrong_timestamp_and_environment_type_rejected(self):
        for value in ["2026-01-01", "2099-01-01T00:00:00Z", "not-a-date", True]:
            with self.subTest(timestamp=value):
                self.document = fixture()
                gate = close_gate(self.document)
                gate["evidence"][0]["executed_at"] = value
                self.assertRejected("executed_at")
        gate["evidence"][0]["environment"] = "staging"
        self.assertRejected("environment")

    def test_deployed_gate_requires_artifact_and_configuration_digests(self):
        gate = close_gate(self.document, "TLS-02")
        del gate["evidence"][0]["artifact_digest"]
        del gate["evidence"][0]["configuration_fingerprint"]
        self.assertRejected("artifact_digest")
        self.assertRejected("configuration_fingerprint")

    def test_dependencies_unknown_self_duplicate_cycle_and_open_rejected(self):
        for dependencies, expected in [(["UNKNOWN"], "other inherited"), (["BUILD-02"], "other inherited"),
                                       (["TLS-02", "TLS-02"], "duplicate dependency")]:
            with self.subTest(dependencies=dependencies):
                self.document = fixture()
                self.document["gates"][0]["dependencies"] = dependencies
                self.assertRejected(expected)
        self.document = fixture()
        self.document["gates"][0]["dependencies"] = ["TLS-02"]
        self.document["gates"][5]["dependencies"] = ["BUILD-02"]
        self.assertRejected("cycle detected")
        self.document = fixture()
        close_gate(self.document)["dependencies"] = ["TLS-02"]
        self.assertRejected("open dependency")

    def test_notice_na_requires_named_binding_waiver_and_both_authorities(self):
        gate = close_gate(self.document, "NOTICE-04")
        gate.update({"status": "NOT APPLICABLE", "evidence": []})
        self.assertRejected("waiver")
        self.assertRejected("Leader and independent-security")
        gate["waiver"] = {
            "decision_maker": "Leader and security reviewers", "authority": "Release decision authority",
            "risk": "Recorded risk acceptance", "scope": "Approved backend candidate",
            "review_conditions": "Revisit if implementation or availability requirements change",
            "reference": "https://github.com/hhtuann/Quizopia_2.0/pull/84",
            "release_candidate_sha": "a" * 40, "decided_at": "2026-01-01T12:00:00Z",
        }
        gate["approvals"] = [approval("LEADER"), approval("INDEPENDENT_SECURITY")]
        self.assertEqual([], self.errors())
        gate["waiver"]["risk"] = "TBD"
        self.assertRejected("placeholder")

    def test_other_gate_cannot_be_silently_waived(self):
        gate = close_gate(self.document)
        gate["status"] = "NOT APPLICABLE"
        self.assertRejected("only NOTICE-04")

    def test_final_approval_requires_both_authorities_and_all_technical_dependencies(self):
        gate = close_gate(self.document, "APPROVAL-01")
        gate["dependencies"] = ["TLS-02"]
        self.assertRejected("required approval kinds")
        self.assertRejected("open dependency")

    def test_gate_specific_authorities_and_independent_reviewer_required(self):
        gate = close_gate(self.document, "PRIVACY-02")
        self.assertRejected("INDEPENDENT_SECURITY, LEADER")
        gate["approvals"] = [approval("LEADER"), approval("INDEPENDENT_SECURITY")]
        gate["approvals"][1]["reviewer"] = gate["evidence"][0]["executor"]
        self.assertRejected("independent of")

    def test_unknown_and_pending_verified_identities_rejected_without_prose_blacklist(self):
        gate = close_gate(self.document)
        gate["dri"] = "unknown"
        gate["evidence"][0]["executor"] = "pending"
        self.assertRejected("verified identity")

    def test_placeholder_tools_results_and_environment_are_not_verified_evidence(self):
        for key, value in [("tools", ["TBD"]), ("actual_result", "pending"), ("expected_result", "unknown"),
                           ("environment", {"name": "pending", "classification": "CI"})]:
            with self.subTest(field=key):
                self.document = fixture()
                close_gate(self.document)["evidence"][0][key] = value
                self.assertRejected("verified")

    def test_leader_policy_authority_can_be_accountable_dri_with_separate_security_review(self):
        gate = close_gate(self.document, "PRIVACY-02")
        gate["approvals"] = [approval("LEADER"), approval("INDEPENDENT_SECURITY")]
        gate["approvals"][0]["reviewer"] = gate["dri"]
        self.assertEqual([], self.errors())

    def test_deployment_approval_cannot_transfer_to_untested_configuration(self):
        gate = close_gate(self.document, "TLS-02")
        gate["approvals"] = [approval(), approval("INDEPENDENT_SECURITY")]
        gate["approvals"][0]["configuration_fingerprint"] = "e" * 64
        self.assertRejected("must match tested deployment")

    def test_notice_waiver_does_not_pretend_runtime_dependencies_passed(self):
        gate = close_gate(self.document, "NOTICE-04")
        gate.update({"status": "NOT APPLICABLE", "evidence": [], "dependencies": ["TLS-02"]})
        gate["waiver"] = {
            "decision_maker": "Leader and security reviewers", "authority": "Release decision authority",
            "risk": "Recorded risk acceptance", "scope": "Approved backend candidate",
            "review_conditions": "Revisit when the scoped candidate changes",
            "reference": "https://github.com/hhtuann/Quizopia_2.0/pull/84",
            "release_candidate_sha": "a" * 40, "decided_at": "2026-01-01T12:00:00Z",
        }
        gate["approvals"] = [approval("LEADER"), approval("INDEPENDENT_SECURITY")]
        self.assertEqual([], self.errors())

    def test_legitimate_synthetic_staging_actors_do_not_make_evidence_placeholder(self):
        gate = close_gate(self.document)
        gate["evidence"][0]["procedure"] = "Verify synthetic staging actor inbox"
        gate["evidence"][0]["actual_result"] = "No pending notices or unknown closure states"
        self.assertEqual([], self.errors())

    def test_production_go_cannot_be_created_by_registry(self):
        self.document["verdict"] = "PRODUCTION GO"
        self.assertRejected("preserve PRODUCTION NO-GO")

    def test_duplicate_yaml_keys_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "duplicate.yaml"
            path.write_text("schema_version: 1\nschema_version: 2\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "duplicate YAML key"):
                validator.load_register(path)

    def test_pinned_matrix_is_actually_verified(self):
        with tempfile.TemporaryDirectory() as directory:
            errors = validator.validate_register(self.document, root=Path(directory))
            self.assertTrue(any("cannot verify pinned matrix" in item for item in errors), errors)


if __name__ == "__main__":
    unittest.main(verbosity=2)
