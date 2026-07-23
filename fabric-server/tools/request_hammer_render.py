#!/usr/bin/env python3
"""Запрашивает у запущенного клиента настоящий игровой QA-рендер молота."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path


def default_root() -> Path:
    appdata = Path(os.environ.get("APPDATA", Path.home() / "AppData" / "Roaming"))
    return appdata / ".eclipse-rp" / "eclipse-debug" / "hammer-render"


def read_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("full", "idle", "motion"), default="full")
    parser.add_argument("--root", type=Path, default=default_root())
    parser.add_argument("--timeout", type=float, default=90.0)
    parser.add_argument("--no-wait", action="store_true")
    parser.add_argument("--skip-inspect", action="store_true",
                        help="Не запускать валидатор и contact-sheet после захвата")
    args = parser.parse_args()

    args.root.mkdir(parents=True, exist_ok=True)
    latest_path = args.root / "latest.json"
    previous_id = read_json(latest_path).get("id")
    error_path = args.root / "last-error.json"
    previous_error_timestamp = read_json(error_path).get("timestamp")
    request_path = args.root / "capture.request.json"
    temporary = request_path.with_suffix(".tmp")
    temporary.write_text(json.dumps({"mode": args.mode}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(request_path)
    print(f"HAMMER_QA_REQUESTED mode={args.mode} request={request_path}")
    if args.no_wait:
        return 0

    deadline = time.monotonic() + args.timeout
    while time.monotonic() < deadline:
        error = read_json(error_path)
        latest = read_json(latest_path)
        if latest.get("id") and latest.get("id") != previous_id:
            if latest.get("error"):
                print(f"HAMMER_QA_FAILED error={latest['error']}")
                return 2
            if latest.get("complete"):
                print(f"HAMMER_QA_COMPLETE directory={latest['directory']} captured={latest.get('captured', 0)}")
                if not args.skip_inspect:
                    inspector = Path(__file__).with_name("inspect_hammer_render.py")
                    result = subprocess.run([sys.executable, str(inspector), latest["directory"]])
                    if result.returncode != 0:
                        print("HAMMER_QA_INSPECTION_FAILED")
                        return 4
                return 0
        if (error.get("timestamp") and error.get("timestamp") != previous_error_timestamp
                and not request_path.exists()):
            print(f"HAMMER_QA_FAILED error={error.get('error', 'unknown')}")
            return 2
        time.sleep(0.25)
    print("HAMMER_QA_TIMEOUT: клиент не завершил захват; проверьте, что игра запущена и молот в основной руке")
    return 3


if __name__ == "__main__":
    raise SystemExit(main())
