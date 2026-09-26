#!/usr/bin/env python3
"""Hold a per-server OS lock for a lifecycle command (macOS/Linux)."""
import fcntl
from pathlib import Path
import subprocess
import sys


def main():
    if len(sys.argv) < 3:
        raise SystemExit("Usage: with-server-lock.py LOCK_FILE COMMAND [ARGS...]")
    lock_path = Path(sys.argv[1])
    # Keep the inode: unlinking a lock file would let another caller bypass it.
    with lock_path.open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print(f"{lock_path.stem}: another server command is in progress; retry when it finishes.", file=sys.stderr)
            return 1
        return subprocess.run(sys.argv[2:]).returncode


if __name__ == "__main__":
    sys.exit(main())
