import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest

import resource_guard


class ResourceGuardTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.report = Path(self.directory.name) / "report.json"

    def tearDown(self):
        self.directory.cleanup()

    def run_child(self, body, max_mib=256, timeout=5):
        code = resource_guard.supervise([sys.executable, "-c", body],
                                       max_mib * 1024 * 1024, timeout, self.report)
        return code, json.loads(self.report.read_text())

    def test_success_and_real_memory_accounting(self):
        code, report = self.run_child("import time; time.sleep(.15)")
        self.assertEqual(0, code)
        self.assertEqual("completed", report["reason"])
        self.assertGreater(report["peak_footprint_bytes"], 0)
        self.assertGreater(report["peak_rss_bytes"], 0)

    def test_nonzero_child_status_is_not_hidden(self):
        code, report = self.run_child("raise SystemExit(7)")
        self.assertEqual(7, code)
        self.assertEqual(7, report["child_exit"])

    def test_memory_kills_before_runaway_growth(self):
        code, report = self.run_child(
            "import time\nblocks=[]\nwhile True:\n"
            " blocks.append(bytearray(b'x' * (4*1024*1024)))\n time.sleep(.02)",
            max_mib=64)
        self.assertEqual(125, code)
        self.assertEqual("memory-limit", report["reason"])
        self.assertEqual(-signal.SIGKILL, report["child_exit"])
        self.assertLess(report["peak_footprint_bytes"], 160 * 1024 * 1024)

    def test_wall_time_kills_sleeping_child(self):
        code, report = self.run_child("import time; time.sleep(20)", timeout=.2)
        self.assertEqual(124, code)
        self.assertEqual("timeout", report["reason"])
        self.assertLess(report["elapsed_seconds"], 2)

    def test_children_share_the_memory_budget(self):
        code, report = self.run_child(
            "import subprocess,sys,time\n"
            "child='import time; x=bytearray(b\"x\"*(48*1024*1024)); time.sleep(20)'\n"
            "subprocess.Popen([sys.executable,'-c',child])\n"
            "x=bytearray(b'x'*(48*1024*1024)); time.sleep(20)", max_mib=96)
        self.assertEqual(125, code)
        self.assertEqual("memory-limit", report["reason"])

    def test_orphaned_group_is_not_left_running(self):
        code, report = self.run_child(
            "import subprocess,sys\n"
            "subprocess.Popen([sys.executable,'-c','import time; time.sleep(20)'])",
            timeout=.4)
        self.assertEqual(124, code)
        self.assertEqual("timeout", report["reason"])

    def test_supervisor_signal_kills_owned_group(self):
        child_pid_file = Path(self.directory.name) / "pid"
        body = (f"import os,time; open({str(child_pid_file)!r},'w').write(str(os.getpid())); "
                "time.sleep(20)")
        supervisor = subprocess.Popen([
            sys.executable, str(Path(resource_guard.__file__)),
            "--max-mib", "128", "--timeout", "10", "--report", str(self.report),
            "--", sys.executable, "-c", body])
        try:
            deadline = time.monotonic() + 3
            while not child_pid_file.exists() and time.monotonic() < deadline:
                time.sleep(.02)
            self.assertTrue(child_pid_file.exists())
            child_pid = int(child_pid_file.read_text())
            supervisor.send_signal(signal.SIGTERM)
            self.assertEqual(130, supervisor.wait(timeout=3))
            self.assertEqual("interrupted", json.loads(self.report.read_text())["reason"])
            with self.assertRaises(ProcessLookupError):
                os.kill(child_pid, 0)
        finally:
            if supervisor.poll() is None:
                supervisor.kill()
                supervisor.wait()


if __name__ == "__main__":
    unittest.main()
