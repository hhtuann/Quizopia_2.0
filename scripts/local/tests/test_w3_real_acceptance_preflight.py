"""Read-only W3 preflight guard tests; no Docker or network required."""

import importlib.util
from pathlib import Path
import socket
import unittest
from unittest.mock import MagicMock, patch

SCRIPT = Path(__file__).resolve().parents[1] / "w3-real-acceptance-preflight.py"
SPEC = importlib.util.spec_from_file_location("w3_preflight", SCRIPT)
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class PreflightTest(unittest.TestCase):
    def test_port_probe_sets_reuseaddr_before_bind(self):
        probe = MagicMock()
        probe.__enter__.return_value = probe
        with patch.object(module.socket, "socket", return_value=probe):
            self.assertTrue(module.port_free(3000))

        probe.setsockopt.assert_called_once_with(
            socket.SOL_SOCKET, socket.SO_REUSEADDR, 1
        )
        probe.bind.assert_called_once_with(("127.0.0.1", 3000))
        probe.listen.assert_called_once_with(1)

    def test_occupied_port_is_detected(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
            server.bind(("127.0.0.1", 0))
            server.listen(1)
            port = server.getsockname()[1]
            self.assertFalse(module.port_free(port))
        self.assertTrue(module.port_free(port))

    def test_failed_or_missing_command_is_not_success(self):
        self.assertIsNone(module.run(["nonexistent-w3-preflight-command-123456"]))

    def test_never_contains_destructive_host_commands(self):
        text = SCRIPT.read_text()
        for destructive in ("docker stop", "docker rm", "docker run", "git reset", "git clean", "kill -9"):
            self.assertNotIn(destructive, text)

    def test_occupied_port_notes_prohibit_silent_reuse(self):
        text = SCRIPT.read_text()
        self.assertIn("Existing frontend on 3000 must not be reused without ownership verification", text)
        self.assertIn("Legacy W3-A launcher requires unused loopback 8080; do not terminate another listener", text)


if __name__ == "__main__":
    unittest.main()
