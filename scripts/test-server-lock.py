#!/usr/bin/env python3
"""Verify concurrent lifecycle calls are rejected and locks release on failure."""
import fcntl
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class ServerLockTest(unittest.TestCase):
    def test_contention_and_release_after_failed_command(self):
        helper = Path(__file__).with_name("with-server-lock.py")
        with tempfile.TemporaryDirectory() as directory:
            lock_path = Path(directory) / "frontend.lock"
            marker = Path(directory) / "started"
            command = [sys.executable, str(helper), str(lock_path), sys.executable, "-c",
                       "import pathlib,sys; pathlib.Path(sys.argv[1]).touch()", str(marker)]
            with lock_path.open("a") as lock:
                fcntl.flock(lock, fcntl.LOCK_EX)
                result = subprocess.run(command, capture_output=True, text=True)
                self.assertEqual(1, result.returncode)
                self.assertIn("in progress", result.stderr)
                self.assertFalse(marker.exists())
            failed = subprocess.run(command[:3] + [sys.executable, "-c", "raise SystemExit(7)"])
            self.assertEqual(7, failed.returncode)
            self.assertEqual(0, subprocess.run(command).returncode)
            self.assertTrue(marker.exists())


if __name__ == "__main__":
    unittest.main()
