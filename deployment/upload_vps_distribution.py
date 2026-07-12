#!/usr/bin/env python3
"""Package and upload the Eclipse distribution tree directly to the VPS file server."""

import os
import sys
import subprocess
import tarfile
import tempfile
from pathlib import Path

SSH_KEY = "E:\\eclipse-stock.pem"
VPS_HOST = "13.51.232.191"
VPS_USER = "ubuntu"
VPS_DEST_DIR = "/var/eclipse-dist"

def make_tarfile(output_filename, source_dir):
    with tarfile.open(output_filename, "w:gz") as tar:
        tar.add(source_dir, arcname=".")

def main():
    repo_dir = Path(__file__).resolve().parents[1]
    source_dir = repo_dir / "distribution-build"
    if not source_dir.is_dir():
        print(f"Distribution directory does not exist: {source_dir}")
        sys.exit(1)

    print("Archiving distribution...")
    with tempfile.TemporaryDirectory() as tmpdir:
        tar_path = Path(tmpdir) / "dist.tar.gz"
        make_tarfile(tar_path, source_dir)
        print(f"Archive created: {tar_path.name} ({tar_path.stat().st_size} bytes)")

        print("Uploading archive to VPS...")
        scp_args = [
            "scp",
            "-i", SSH_KEY,
            "-o", "StrictHostKeyChecking=no",
            str(tar_path),
            f"{VPS_USER}@{VPS_HOST}:/tmp/dist.tar.gz"
        ]
        subprocess.run(scp_args, check=True)
        print("Archive uploaded successfully.")

    print("Extracting archive on VPS and setting permissions...")
    remote_cmds = (
        f"sudo mkdir -p {VPS_DEST_DIR} && "
        f"sudo rm -rf {VPS_DEST_DIR}/* && "
        f"sudo tar -xzf /tmp/dist.tar.gz -C {VPS_DEST_DIR} && "
        f"sudo chown -R root:root {VPS_DEST_DIR} && "
        f"sudo chmod -R 755 {VPS_DEST_DIR} && "
        f"rm -f /tmp/dist.tar.gz"
    )
    ssh_args = [
        "ssh",
        "-i", SSH_KEY,
        "-o", "StrictHostKeyChecking=no",
        f"{VPS_USER}@{VPS_HOST}",
        remote_cmds
    ]
    subprocess.run(ssh_args, check=True)
    print("VPS distribution update complete!")

if __name__ == "__main__":
    main()
