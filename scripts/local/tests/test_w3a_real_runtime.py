"""Owned-runtime cleanup regressions; no real processes or Docker are touched."""

import importlib.util
import io
import json
from pathlib import Path
import signal
import subprocess
import tempfile
import unittest
from unittest.mock import call, patch

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
        with patch.object(runtime, "process_owned", side_effect=[FileNotFoundError(), 82]), \
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
        with patch.object(runtime, "process_owned", side_effect=FileNotFoundError()), \
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
        with patch.object(runtime, "process_owned", side_effect=FileNotFoundError()), \
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


class ExitWaitTests(unittest.TestCase):
    def test_non_exiting_process_wait_has_a_bounded_deadline(self):
        with patch.object(runtime, "process_owned", return_value=81), \
                patch.object(runtime.time, "monotonic", side_effect=[0, 0, 6]), \
                patch.object(runtime.time, "sleep") as sleep:
            self.assertFalse(runtime.wait_for_exit(Path("/private/fixture"), "quiz", timeout=5))
        sleep.assert_called_once_with(0.05)


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


if __name__ == "__main__":
    unittest.main()
