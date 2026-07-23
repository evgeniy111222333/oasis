#!/usr/bin/env python3
"""Publish a release push and its current Eclipse client distribution."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--title", required=True)
    parser.add_argument("--summary", required=True)
    parser.add_argument("--note", action="append", default=[])
    parser.add_argument("--id")
    parser.add_argument("--button-label", default="ЗАГРУЗИТЬ ОБНОВЛЕНИЕ")
    parser.add_argument("--success-message", default="Обновление загружено и установлено.")
    args = parser.parse_args()

    deployment_dir = Path(__file__).resolve().parent
    repo = deployment_dir.parent
    fabric_build = repo / "fabric-server" / "build.ps1"
    if not fabric_build.is_file():
        raise FileNotFoundError(f"Authoritative Fabric build script is missing: {fabric_build}")
    # Always compile and verify both Fabric sides from the authoritative source tree.
    # This also refreshes the exact client/server JARs consumed by the release stages.
    subprocess.run([
        "powershell.exe",
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", str(fabric_build),
    ], cwd=repo, check=True)
    now = datetime.now(timezone.utc)
    release = {
        "id": args.id or now.strftime("%Y%m%d-%H%M%S"),
        "published": True,
        "publishedAt": now.isoformat().replace("+00:00", "Z"),
        "title": args.title.strip(),
        "summary": args.summary.strip(),
        "notes": [note.strip() for note in args.note if note.strip()],
        "buttonLabel": args.button_label.strip(),
        "successMessage": args.success_message.strip(),
    }
    (deployment_dir / "release.json").write_text(
        json.dumps(release, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    subprocess.run([sys.executable, str(deployment_dir / "build_distribution.py")], cwd=repo, check=True)
    # Bring the authoritative Fabric gameplay implementation online before clients receive
    # the matching release. The deployer skips restarts when the production checksum is current
    # and performs an automatic rollback if startup/readiness verification fails.
    subprocess.run([sys.executable, str(deployment_dir / "deploy_vps_server_mod.py")], cwd=repo, check=True)
    drive_config = deployment_dir / "google_drive.local.json"
    if drive_config.exists():
        try:
            drive_script = deployment_dir / "upload_google_drive_distribution.py"
            subprocess.run([sys.executable, str(drive_script)], cwd=repo, check=True)
            # Rebuild so the public manifest contains the Drive browser-download links,
            # then update only that manifest inside the immutable release folder.
            subprocess.run([sys.executable, str(deployment_dir / "build_distribution.py")], cwd=repo, check=True)
            subprocess.run([sys.executable, str(drive_script), "--manifest-only"], cwd=repo, check=True)
        except subprocess.CalledProcessError as error:
            print(f"WARNING: Google Drive mirror was not published: {error}", file=sys.stderr)
    subprocess.run([sys.executable, str(deployment_dir / "upload_r2_distribution.py")], cwd=repo, check=True)
    subprocess.run([sys.executable, str(deployment_dir / "upload_vps_distribution.py")], cwd=repo, check=True)
    subprocess.run([sys.executable, str(deployment_dir / "upload_github_distribution.py")], cwd=repo, check=True)
    print(f"Published release push {release['id']}")


if __name__ == "__main__":
    main()
