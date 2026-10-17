"""Owned-runtime cleanup regressions; no real processes or Docker are touched."""

import importlib.util
import io
import json
from pathlib import Path
import signal
import subprocess
import tempfile
import unittest
from unittest.mock import call, patch, MagicMock

SPEC = importlib.util.spec_from_file_location(
    "w3a_runtime", Path(__file__).resolve().parents[1] / "w3a-real-runtime.py"
)
runtime = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runtime)


class CleanupTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.state = {
            "fixture": "w3a-real-browser-v1",
            "resource_prefix": "owned-fixture",
            "service_jars": {"quiz": "private/quiz.jar", "gateway": "private/gateway.jar"},
            "processes": [],
            "containers": ["owned-postgres", "owned-redis"],
            "quiz_paused": True,
            "gateway_paused": False,
        }
        (self.directory / "state.json").write_text(json.dumps(self.state))
        self.owned_inspection = subprocess.CompletedProcess([], 0, "owned-fixture\n", "")
        waiter = patch.object(runtime, "wait_for_exit", return_value=True)
        self.waiter = waiter.start()
        self.addCleanup(waiter.stop)

    def test_exited_paused_process_does_not_skip_remaining_cleanup(self):
        with patch.object(runtime, "process_owned", side_effect=[ProcessLookupError(), 82]), \
                patch.object(runtime.os, "kill") as kill, \
                patch.object(runtime.subprocess, "run", return_value=self.owned_inspection), \
                patch.object(runtime.owned, "run") as remove:
            runtime.down(self.directory)
        kill.assert_called_once_with(82, signal.SIGTERM)
        self.assertEqual(remove.call_count, 2)
        recorded = json.loads((self.directory / "state.json").read_text())
        self.assertEqual(recorded["cleanup_unverified"], [])
        self.assertIn("stopped_at", recorded)

    def test_replacement_pid_is_never_signaled_but_other_resources_are_cleaned(self):
        with patch.object(runtime, "process_owned", side_effect=[RuntimeError("ownership mismatch"), 82]), \
                patch.object(runtime.os, "kill") as kill, \
                patch.object(runtime.subprocess, "run", return_value=self.owned_inspection), \
                patch.object(runtime.owned, "run") as remove:
            runtime.down(self.directory)
        kill.assert_called_once_with(82, signal.SIGTERM)
        self.assertEqual(remove.call_count, 2)
        recorded = json.loads((self.directory / "state.json").read_text())
        self.assertEqual(recorded["cleanup_unverified"], ["quiz"])
        self.assertNotIn("stopped_at", recorded)

    def test_verified_paused_process_is_resumed_before_termination(self):
        with patch.object(runtime, "process_owned", side_effect=[81, 82]), \
                patch.object(runtime.os, "kill") as kill, \
                patch.object(runtime.subprocess, "run", return_value=self.owned_inspection), \
                patch.object(runtime.owned, "run"):
            runtime.down(self.directory)
        self.assertEqual(kill.call_args_list, [call(81, signal.SIGCONT), call(81, signal.SIGTERM), call(82, signal.SIGTERM)])

    def test_foreign_container_is_never_removed(self):
        foreign = subprocess.CompletedProcess([], 0, "another-owner\n", "")
        with patch.object(runtime, "process_owned", side_effect=ProcessLookupError()), \
                patch.object(runtime.os, "kill") as kill, \
                patch.object(runtime.subprocess, "run", side_effect=[foreign, self.owned_inspection]), \
                patch.object(runtime.owned, "run") as remove:
            runtime.down(self.directory)
        kill.assert_not_called()
        self.assertEqual(remove.call_args.args[0], ["docker", "rm", "-f", "-v", "owned-postgres"])
        recorded = json.loads((self.directory / "state.json").read_text())
        self.assertEqual(recorded["cleanup_unverified"], ["owned-redis"])

    def test_docker_inspect_failure_never_claims_verified_cleanup(self):
        unavailable = subprocess.CompletedProcess([], 1, "", "Docker unavailable")
        with patch.object(runtime, "process_owned", side_effect=ProcessLookupError()), \
                patch.object(runtime.subprocess, "run", return_value=unavailable), \
                patch.object(runtime.owned, "run") as remove:
            runtime.down(self.directory)
        remove.assert_not_called()
        recorded = json.loads((self.directory / "state.json").read_text())
        self.assertEqual(recorded["cleanup_unverified"], ["owned-redis", "owned-postgres"])
        self.assertNotIn("stopped_at", recorded)

    def test_process_not_exiting_is_recorded_without_skipping_container_cleanup(self):
        self.waiter.return_value = False
        with patch.object(runtime, "process_owned", side_effect=[81, 82]), \
                patch.object(runtime.os, "kill"), \
                patch.object(runtime.subprocess, "run", return_value=self.owned_inspection), \
                patch.object(runtime.owned, "run") as remove:
            runtime.down(self.directory)
        self.assertEqual(remove.call_count, 2)
        self.assertEqual(self.waiter.call_count, 2)
        recorded = json.loads((self.directory / "state.json").read_text())
        self.assertEqual(recorded["cleanup_unverified"], ["quiz", "gateway"])
        self.assertNotIn("stopped_at", recorded)

    def test_missing_runtime_state_during_down_cannot_claim_cleanup(self):
        (self.directory / "state.json").unlink()
        with patch("sys.stdout", new_callable=io.StringIO) as output:
            runtime.down(self.directory)
        self.assertIn("Unverified resources left untouched: runtime state missing or invalid", output.getvalue())
        self.assertFalse((self.directory / "state.json").exists())


class ExitWaitTests(unittest.TestCase):
    def test_non_exiting_process_wait_has_a_bounded_deadline(self):
        with patch.object(runtime, "process_owned", return_value=81), \
                patch.object(runtime.time, "monotonic", side_effect=[0, 0, 6]), \
                patch.object(runtime.time, "sleep") as sleep:
            self.assertFalse(runtime.wait_for_exit(Path("/private/fixture"), "quiz", timeout=5))
        sleep.assert_called_once_with(0.05)

    def test_genuine_process_absence_is_handled_with_process_lookup_error(self):
        with patch.object(runtime, "process_owned", side_effect=ProcessLookupError()):
            self.assertTrue(runtime.wait_for_exit(Path("/private/fixture"), "quiz", timeout=5))

    def test_missing_runtime_state_during_wait_for_exit_fails_closed(self):
        with patch.object(runtime, "process_owned", side_effect=RuntimeError("Runtime state is missing or unreadable")):
            self.assertFalse(runtime.wait_for_exit(Path("/private/fixture"), "quiz", timeout=5))


class QueueWitnessTests(unittest.TestCase):
    def test_only_owned_quiz_queued_bytes_establish_forwarding(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            (directory / "state.json").write_text(json.dumps({"endpoints": {"quiz": "http://127.0.0.1:38281"}}))
            for rows, expected in [
                ('ESTAB 50 0 127.0.0.1:38281 127.0.0.1:50000 users:(("java",pid=81,fd=1))', "1\n"),
                ('ESTAB 50 0 127.0.0.1:38281 127.0.0.1:50000 users:(("java",pid=82,fd=1))', "0\n"),
                ('ESTAB 0 50 127.0.0.1:38281 127.0.0.1:50000 users:(("java",pid=81,fd=1))', "0\n"),
            ]:
                with self.subTest(rows=rows), patch.object(runtime, "process_owned", return_value=81), \
                        patch.object(runtime.owned, "run", return_value=subprocess.CompletedProcess([], 0, rows, "")) as run, \
                        patch("sys.stdout", new_callable=io.StringIO) as output:
                    runtime.quiz_pending_request(directory)
                    self.assertEqual(output.getvalue(), expected)
                    self.assertEqual(run.call_args.args[0], ["ss", "-tnp", "( sport = :38281 )"])


class FlagDefaultTests(unittest.TestCase):
    def test_closure_worker_and_edge_inbox_attestation_default_off(self):
        text = (Path(__file__).resolve().parents[1] / "w3a-real-runtime.py").read_text()
        self.assertIn('closure_worker = os.environ.get("W3A_ASSESSMENT_CLOSURE_WORKER_ENABLED", "false")', text)
        self.assertIn('edge_inbox_verified = os.environ.get("W3A_ASSESSMENT_EDGE_INBOX_VERIFIED", "false")', text)
        self.assertIn('recipient_eligibility = os.environ.get("W3A_ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED", "false")', text)
        self.assertIn('identity_notice_recipients = os.environ.get("W3A_IDENTITY_NOTICE_RECIPIENTS_ENABLED", "false")', text)


class ActivateEdgeAttestationTests(unittest.TestCase):
    def test_activate_edge_attestation_restarts_assessment_with_attestation_only(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            jar_path = "/private/assessment.jar"
            state = {
                "fixture": "w3a-real-browser-v1",
                "service_jars": {"assessment": jar_path},
                "processes": [{"pid": 101, "jar": jar_path}],
                "test_only_flags": {
                    "ASSESSMENT_EDGE_INBOX_VERIFIED": "false",
                    "ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED": "false",
                    "ASSESSMENT_CLOSURE_WORKER_ENABLED": "false",
                },
                "endpoints": {"assessment": "http://127.0.0.1:45678"},
            }
            (directory / "state.json").write_text(json.dumps(state))
            (directory / "assessment.env").write_text("SERVER_PORT=45678\nASSESSMENT_CLOSURE_WORKER_ENABLED=false\n")

            mock_process = unittest.mock.MagicMock()
            mock_process.pid = 202

            with patch.object(runtime, "process_owned", return_value=101), \
                    patch.object(runtime, "wait_for_exit", return_value=True), \
                    patch.object(runtime.os, "kill") as kill, \
                    patch.object(runtime.subprocess, "Popen", return_value=mock_process) as popen, \
                    patch.object(runtime.owned, "wait_http") as wait_http, \
                    patch("sys.stdout", new_callable=io.StringIO) as output:
                runtime.activate_edge_attestation(directory)

            kill.assert_called_once_with(101, signal.SIGTERM)
            self.assertIn("PASS operator activation: edge inbox attestation activated; closure worker remains OFF", output.getvalue())
            wait_http.assert_called_once_with("http://127.0.0.1:45678/actuator/health")

            updated_env = (directory / "assessment.env").read_text()
            self.assertIn("ASSESSMENT_EDGE_INBOX_VERIFIED=true", updated_env)
            self.assertIn("ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED=true", updated_env)
            self.assertIn("ASSESSMENT_CLOSURE_WORKER_ENABLED=false", updated_env)

            updated_state = json.loads((directory / "state.json").read_text())
            self.assertEqual(updated_state["processes"], [{"pid": 202, "jar": jar_path}])
            self.assertEqual(updated_state["test_only_flags"]["ASSESSMENT_EDGE_INBOX_VERIFIED"], "true")
            self.assertEqual(updated_state["test_only_flags"]["ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED"], "true")
            self.assertEqual(updated_state["test_only_flags"]["ASSESSMENT_CLOSURE_WORKER_ENABLED"], "false")


class ActivateClosureWorkerTests(unittest.TestCase):
    def test_activate_closure_worker_restarts_assessment_with_enabled_flags(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            jar_path = "/private/assessment.jar"
            state = {
                "fixture": "w3a-real-browser-v1",
                "service_jars": {"assessment": jar_path},
                "processes": [{"pid": 101, "jar": jar_path}],
                "test_only_flags": {
                    "ASSESSMENT_EDGE_INBOX_VERIFIED": "false",
                    "ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED": "false",
                    "ASSESSMENT_CLOSURE_WORKER_ENABLED": "false",
                },
                "endpoints": {"assessment": "http://127.0.0.1:45678"},
            }
            (directory / "state.json").write_text(json.dumps(state))
            (directory / "assessment.env").write_text("SERVER_PORT=45678\nASSESSMENT_CLOSURE_WORKER_ENABLED=false\n")

            mock_process = unittest.mock.MagicMock()
            mock_process.pid = 202

            with patch.object(runtime, "process_owned", return_value=101), \
                    patch.object(runtime, "wait_for_exit", return_value=True), \
                    patch.object(runtime.os, "kill") as kill, \
                    patch.object(runtime.subprocess, "Popen", return_value=mock_process) as popen, \
                    patch.object(runtime.owned, "wait_http") as wait_http, \
                    patch("sys.stdout", new_callable=io.StringIO) as output:
                runtime.activate_closure_worker(directory, "4000")

            kill.assert_called_once_with(101, signal.SIGTERM)
            self.assertIn("PASS operator activation", output.getvalue())
            wait_http.assert_called_once_with("http://127.0.0.1:45678/actuator/health")

            updated_env = (directory / "assessment.env").read_text()
            self.assertIn("ASSESSMENT_EDGE_INBOX_VERIFIED=true", updated_env)
            self.assertIn("ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED=true", updated_env)
            self.assertIn("ASSESSMENT_CLOSURE_WORKER_ENABLED=true", updated_env)
            self.assertIn("QUIZOPIA_ASSESSMENT_CLOSURE_POLL_MILLIS=4000", updated_env)

            updated_state = json.loads((directory / "state.json").read_text())
            self.assertEqual(updated_state["processes"], [{"pid": 202, "jar": jar_path}])
            self.assertEqual(updated_state["test_only_flags"]["ASSESSMENT_CLOSURE_WORKER_ENABLED"], "true")
            self.assertEqual(updated_state["test_only_flags"]["QUIZOPIA_ASSESSMENT_CLOSURE_POLL_MILLIS"], "4000")


class ResetClosureFlagsTests(unittest.TestCase):
    def test_reset_closure_flags_restores_initial_defaults(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            jar_path = str(directory / "assessment.jar")
            Path(jar_path).write_text("fake-jar")
            (directory / "assessment.env").write_text("SERVER_PORT=45678\nASSESSMENT_CLOSURE_WORKER_ENABLED=true\nASSESSMENT_EDGE_INBOX_VERIFIED=true\n")

            state = {
                "fixture": "w3a-real-browser-v1",
                "service_jars": {"assessment": jar_path},
                "processes": [{"pid": 101, "jar": jar_path}],
                "endpoints": {"assessment": "http://127.0.0.1:45678"},
                "test_only_flags": {
                    "ASSESSMENT_CLOSURE_WORKER_ENABLED": "true",
                    "ASSESSMENT_EDGE_INBOX_VERIFIED": "true",
                },
            }
            (directory / "state.json").write_text(json.dumps(state))

            mock_process = MagicMock()
            mock_process.pid = 202

            with patch.object(runtime, "process_owned", return_value=101), \
                    patch.object(runtime, "wait_for_exit", return_value=True), \
                    patch.object(runtime.os, "kill") as kill, \
                    patch.object(runtime.owned, "wait_http") as wait_http, \
                    patch.object(runtime.subprocess, "Popen", return_value=mock_process), \
                    patch("sys.stdout", new_callable=io.StringIO) as output:
                runtime.reset_closure_flags(directory)

            kill.assert_called_once_with(101, signal.SIGTERM)
            self.assertIn("PASS operator reset", output.getvalue())
            wait_http.assert_called_once_with("http://127.0.0.1:45678/actuator/health")

            updated_env = (directory / "assessment.env").read_text()
            self.assertIn("ASSESSMENT_EDGE_INBOX_VERIFIED=false", updated_env)
            self.assertIn("ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED=false", updated_env)
            self.assertIn("ASSESSMENT_CLOSURE_WORKER_ENABLED=false", updated_env)

            updated_state = json.loads((directory / "state.json").read_text())
            self.assertEqual(updated_state["test_only_flags"]["ASSESSMENT_CLOSURE_WORKER_ENABLED"], "false")
            self.assertEqual(updated_state["test_only_flags"]["ASSESSMENT_EDGE_INBOX_VERIFIED"], "false")


class MembershipFixtureTests(unittest.TestCase):
    def test_remove_membership_rejects_non_canonical_uuid(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            (directory / "state.json").write_text(json.dumps({"fixture": "w3a-real-browser-v1"}))
            with self.assertRaises(ValueError):
                runtime.remove_membership(directory, "not-a-uuid", "00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002")

    def test_remove_membership_executes_delete_on_owned_container(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            state = {
                "fixture": "w3a-real-browser-v1",
                "resource_prefix": "owned-fixture",
                "database_containers": {"classroom": "owned-classroom-postgres"},
                "containers": ["owned-classroom-postgres"],
            }
            (directory / "state.json").write_text(json.dumps(state))
            cid = "11111111-1111-1111-1111-111111111111"
            sid = "22222222-2222-2222-2222-222222222222"
            tid = "33333333-3333-3333-3333-333333333333"
            with patch.object(runtime.subprocess, "check_output", return_value="owned-fixture\n"), \
                    patch.object(runtime.owned, "run") as run, \
                    patch("sys.stdout", new_callable=io.StringIO):
                runtime.remove_membership(directory, cid, sid, tid)
            self.assertEqual(run.call_count, 1)
            cmd = run.call_args.args[0]
            self.assertIn("owned-classroom-postgres", cmd)
            sql = run.call_args.kwargs["input"].decode()
            self.assertIn("DELETE FROM classroom_memberships", sql)
            self.assertIn(cid, sql)
            self.assertIn(sid, sql)


class RestartAssessmentOwnershipTests(unittest.TestCase):
    def test_initial_ownership_mismatch_fails_closed(self):
        """Initial ownership mismatch must fail closed without signaling or launching a process."""
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            jar_path = "/private/assessment.jar"
            state = {
                "fixture": "w3a-real-browser-v1",
                "service_jars": {"assessment": jar_path},
                "processes": [{"pid": 101, "jar": jar_path}],
                "test_only_flags": {},
                "endpoints": {"assessment": "http://127.0.0.1:45678"},
            }
            (directory / "state.json").write_text(json.dumps(state))
            (directory / "assessment.env").write_text("SERVER_PORT=45678\n")

            with patch.object(runtime, "process_owned", side_effect=RuntimeError("Runtime process ownership does not match")), \
                    patch.object(runtime.os, "kill") as kill, \
                    patch.object(runtime.subprocess, "Popen") as popen:
                with self.assertRaises(RuntimeError) as cm:
                    runtime._restart_assessment(directory, {}, {}, "test")
                self.assertIn("ownership does not match", str(cm.exception))
                kill.assert_not_called()
                popen.assert_not_called()

    def test_pid_replacement_between_term_and_kill_fails_closed(self):
        """If process identity changes between SIGTERM and SIGKILL, must fail closed without SIGKILL or launch."""
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            jar_path = "/private/assessment.jar"
            state = {
                "fixture": "w3a-real-browser-v1",
                "service_jars": {"assessment": jar_path},
                "processes": [{"pid": 101, "jar": jar_path}],
                "test_only_flags": {},
                "endpoints": {"assessment": "http://127.0.0.1:45678"},
            }
            (directory / "state.json").write_text(json.dumps(state))
            (directory / "assessment.env").write_text("SERVER_PORT=45678\n")

            process_owned_calls = [101, RuntimeError("Runtime process ownership does not match")]
            def mock_process_owned(d, name):
                res = process_owned_calls.pop(0)
                if isinstance(res, Exception):
                    raise res
                return res

            with patch.object(runtime, "process_owned", side_effect=mock_process_owned), \
                    patch.object(runtime, "wait_for_exit", return_value=False), \
                    patch.object(runtime.os, "kill") as kill, \
                    patch.object(runtime.subprocess, "Popen") as popen:
                with self.assertRaises(RuntimeError) as cm:
                    runtime._restart_assessment(directory, {}, {}, "test")
                self.assertIn("ownership mismatch before SIGKILL", str(cm.exception))
                kill.assert_called_once_with(101, signal.SIGTERM)
                popen.assert_not_called()

    def test_missing_runtime_state_during_restart_fails_closed(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            # no state.json
            with patch.object(runtime.os, "kill") as kill, \
                    patch.object(runtime.subprocess, "Popen") as popen:
                with self.assertRaises(RuntimeError) as cm:
                    runtime._restart_assessment(directory, {}, {}, "test")
                self.assertIn("Runtime state is missing or unreadable", str(cm.exception))
                kill.assert_not_called()
                popen.assert_not_called()

    def test_missing_state_during_wait_for_exit_prevents_replacement_process(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            jar_path = "/private/assessment.jar"
            state = {
                "fixture": "w3a-real-browser-v1",
                "service_jars": {"assessment": jar_path},
                "processes": [{"pid": 101, "jar": jar_path}],
                "test_only_flags": {},
                "endpoints": {"assessment": "http://127.0.0.1:45678"},
            }
            (directory / "state.json").write_text(json.dumps(state))
            (directory / "assessment.env").write_text("SERVER_PORT=45678\n")

            with patch.object(runtime, "process_owned", side_effect=[101, RuntimeError("Runtime state is missing or unreadable"), RuntimeError("Runtime state is missing or unreadable")]), \
                    patch.object(runtime.os, "kill") as kill, \
                    patch.object(runtime.subprocess, "Popen") as popen:
                with self.assertRaises(RuntimeError) as cm:
                    runtime._restart_assessment(directory, {}, {}, "test")
                self.assertIn("ownership mismatch before SIGKILL", str(cm.exception))
                kill.assert_called_once_with(101, signal.SIGTERM)
                popen.assert_not_called()


class RecipientEligibilityTests(unittest.TestCase):
    def test_probe_recipient_eligibility_rejects_non_canonical_uuid(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            (directory / "state.json").write_text(json.dumps({"fixture": "w3a-real-browser-v1"}))
            with self.assertRaises(ValueError):
                runtime.probe_recipient_eligibility(directory, "not-a-uuid")


class ProcessOwnedTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.jar_path = "/private/assessment.jar"
        self.state = {
            "fixture": "w3a-real-browser-v1",
            "service_jars": {"assessment": self.jar_path},
            "processes": [{"pid": 1234, "jar": self.jar_path}],
        }
        (self.directory / "state.json").write_text(json.dumps(self.state))

    def test_missing_state_raises_runtime_error(self):
        (self.directory / "state.json").unlink()
        with self.assertRaises(RuntimeError) as cm:
            runtime.process_owned(self.directory, "assessment")
        self.assertIn("Runtime state is missing or unreadable", str(cm.exception))

    def test_corrupt_state_raises_runtime_error(self):
        (self.directory / "state.json").write_text("invalid json")
        with self.assertRaises(RuntimeError) as cm:
            runtime.process_owned(self.directory, "assessment")
        self.assertIn("Runtime state is missing or unreadable", str(cm.exception))

    def test_missing_proc_raises_process_lookup_error(self):
        mock_status = MagicMock()
        mock_status.read_text.side_effect = FileNotFoundError()
        mock_proc_pid = MagicMock()
        mock_proc_pid.__truediv__.return_value = mock_status

        mock_proc = MagicMock()
        mock_proc.__truediv__.return_value = mock_proc_pid

        with patch.object(runtime, "Path", side_effect=lambda *a: mock_proc if a == ("/proc",) else Path(*a)):
            with self.assertRaises(ProcessLookupError) as cm:
                runtime.process_owned(self.directory, "assessment")
            self.assertIn("Runtime process is not running", str(cm.exception))

    def test_zombie_state_raises_process_lookup_error(self):
        mock_status = MagicMock()
        mock_status.read_text.return_value = "Name:\tjava\nState:\tZ (zombie)\nTgid:\t1234\n"
        mock_proc_pid = MagicMock()
        mock_proc_pid.__truediv__.return_value = mock_status

        mock_proc = MagicMock()
        mock_proc.__truediv__.return_value = mock_proc_pid

        with patch.object(runtime, "Path", side_effect=lambda *a: mock_proc if a == ("/proc",) else Path(*a)):
            with self.assertRaises(ProcessLookupError) as cm:
                runtime.process_owned(self.directory, "assessment")
            self.assertIn("Runtime process is not running", str(cm.exception))

    def test_empty_cmdline_raises_process_lookup_error(self):
        mock_status = MagicMock()
        mock_status.read_text.return_value = "Name:\tjava\nState:\tS (sleeping)\n"
        mock_cmdline = MagicMock()
        mock_cmdline.read_bytes.return_value = b""

        def sub_path(name):
            if name == "status":
                return mock_status
            if name == "cmdline":
                return mock_cmdline
            return MagicMock()

        mock_proc_pid = MagicMock()
        mock_proc_pid.__truediv__.side_effect = sub_path

        mock_proc = MagicMock()
        mock_proc.__truediv__.return_value = mock_proc_pid

        with patch.object(runtime, "Path", side_effect=lambda *a: mock_proc if a == ("/proc",) else Path(*a)):
            with self.assertRaises(ProcessLookupError) as cm:
                runtime.process_owned(self.directory, "assessment")
            self.assertIn("Runtime process is not running", str(cm.exception))

    def test_ownership_mismatch_raises_runtime_error(self):
        mock_status = MagicMock()
        mock_status.read_text.return_value = "Name:\tjava\nState:\tS (sleeping)\n"
        mock_cmdline = MagicMock()
        mock_cmdline.read_bytes.return_value = b"java\0-jar\0/other/service.jar\0"
        mock_cwd = MagicMock()
        mock_cwd.resolve.return_value = self.directory

        def sub_path(name):
            if name == "status":
                return mock_status
            if name == "cmdline":
                return mock_cmdline
            if name == "cwd":
                return mock_cwd
            return MagicMock()

        mock_proc_pid = MagicMock()
        mock_proc_pid.__truediv__.side_effect = sub_path

        mock_proc = MagicMock()
        mock_proc.__truediv__.return_value = mock_proc_pid

        with patch.object(runtime, "Path", side_effect=lambda *a: mock_proc if a == ("/proc",) else Path(*a)):
            with self.assertRaises(RuntimeError) as cm:
                runtime.process_owned(self.directory, "assessment")
            self.assertIn("Runtime process ownership does not match", str(cm.exception))

    def test_matching_process_returns_pid(self):
        mock_status = MagicMock()
        mock_status.read_text.return_value = "Name:\tjava\nState:\tS (sleeping)\n"
        mock_cmdline = MagicMock()
        mock_cmdline.read_bytes.return_value = b"java\0-jar\0" + self.jar_path.encode() + b"\0"
        mock_cwd = MagicMock()
        mock_cwd.resolve.return_value = self.directory

        def sub_path(name):
            if name == "status":
                return mock_status
            if name == "cmdline":
                return mock_cmdline
            if name == "cwd":
                return mock_cwd
            return MagicMock()

        mock_proc_pid = MagicMock()
        mock_proc_pid.__truediv__.side_effect = sub_path

        mock_proc = MagicMock()
        mock_proc.__truediv__.return_value = mock_proc_pid

        with patch.object(runtime, "Path", side_effect=lambda *a: mock_proc if a == ("/proc",) else Path(*a)):
            pid = runtime.process_owned(self.directory, "assessment")
            self.assertEqual(pid, 1234)


if __name__ == "__main__":
    unittest.main()


