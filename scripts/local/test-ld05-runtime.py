#!/usr/bin/env python3
"""Real owned-process cleanup regression; no Docker, credentials or service access."""
import importlib.util
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest

spec = importlib.util.spec_from_file_location("runtime", Path(__file__).with_name("ld05-real-executor-runtime.py"))
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class BrowserCleanupTests(unittest.TestCase):
    def exercise(self, leader_exits):
        with tempfile.TemporaryDirectory(prefix="quizopia-owned-cleanup-") as temporary:
            directory = Path(temporary)
            frontend = directory / "frontend-source" / "frontend"
            frontend.mkdir(parents=True)
            marker = frontend / "child.pid"
            source = "import subprocess,sys,time; child=subprocess.Popen([sys.executable,'-c','import time;time.sleep(60)'],start_new_session=True);open('child.pid','w').write(str(child.pid));" + ("" if leader_exits else "time.sleep(60)")
            unrelated = subprocess.Popen([sys.executable, "-c", "import time;time.sleep(60)"], cwd=directory,
                                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
            leader = subprocess.Popen([sys.executable, "-c", source], cwd=frontend,
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
            try:
                for _ in range(100):
                    if marker.exists() and marker.read_text():
                        break
                    time.sleep(0.01)
                self.assertTrue(marker.exists())
                child_pid = int(marker.read_text())
                if leader_exits:
                    self.assertEqual(0, leader.wait(timeout=3))
                runtime.stop_owned_browser(directory, leader.pid)
                leader.wait(timeout=5)
                self.assertIsNone(unrelated.poll(), "Unrelated process must survive")
                child_stat = Path("/proc") / str(child_pid) / "stat"
                self.assertTrue(not child_stat.exists() or child_stat.read_text().split()[2] == "Z")
            finally:
                runtime.stop_owned_browser(directory, leader.pid)
                if leader.poll() is None:
                    os.killpg(leader.pid, signal.SIGKILL)
                    leader.wait(timeout=5)
                unrelated.terminate()
                unrelated.wait(timeout=5)

    def test_timeout_cleans_owned_detached_child_and_preserves_unrelated_process(self):
        self.exercise(False)

    def test_finished_leader_does_not_hide_detached_owned_child(self):
        self.exercise(True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
