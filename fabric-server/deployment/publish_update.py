#!/usr/bin/env python3
"""Compatibility entry point for the authoritative repository publisher.

Keep this path for existing operator shortcuts, but execute the single root
orchestrator so build, tests, atomic server deployment and every public mirror
can never drift between two copied scripts.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


def main() -> None:
    publisher = Path(__file__).resolve().parents[2] / "deployment" / "publish_update.py"
    if not publisher.is_file():
        raise FileNotFoundError(f"Authoritative publisher is missing: {publisher}")
    subprocess.run([sys.executable, str(publisher), *sys.argv[1:]], check=True)


if __name__ == "__main__":
    main()
