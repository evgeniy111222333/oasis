#!/usr/bin/env python3
"""Package and upload the Eclipse distribution tree directly to the VPS file server."""

import os
import sys
import subprocess
import tarfile
import tempfile
import hashlib
from pathlib import Path

SSH_KEY = "E:\\eclipse-stock.pem"
VPS_HOST = "13.51.232.191"
VPS_USER = "ubuntu"
VPS_DEST_DIR = "/var/eclipse-dist"
WINDOWS_OPENSSH = Path(os.environ.get("WINDIR", r"C:\Windows")) / "System32" / "OpenSSH"
SCP_COMMAND = str(WINDOWS_OPENSSH / "scp.exe") if (WINDOWS_OPENSSH / "scp.exe").is_file() else "scp"
SSH_COMMAND = str(WINDOWS_OPENSSH / "ssh.exe") if (WINDOWS_OPENSSH / "ssh.exe").is_file() else "ssh"

def make_tarfile(output_filename, source_dir):
    with tarfile.open(output_filename, "w:gz") as tar:
        tar.add(source_dir, arcname=".")

def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def main():
    repo_dir = Path(__file__).resolve().parents[1]
    source_dir = repo_dir / "distribution-build"
    if not source_dir.is_dir():
        print(f"Distribution directory does not exist: {source_dir}")
        sys.exit(1)
    sync_script = repo_dir / "deployment" / "sync_client_manifest_atomic.sh"
    if not sync_script.is_file():
        print(f"Client sync script does not exist: {sync_script}")
        sys.exit(1)

    print("Archiving distribution...")
    with tempfile.TemporaryDirectory() as tmpdir:
        tar_path = Path(tmpdir) / "dist.tar.gz"
        client_tar_path = Path(tmpdir) / "client.tar.gz"
        make_tarfile(tar_path, source_dir)
        with tarfile.open(client_tar_path, "w:gz") as tar:
            tar.add(source_dir / "client", arcname="client")
        client_sha256 = sha256(client_tar_path)
        print(f"Archive created: {tar_path.name} ({tar_path.stat().st_size} bytes)")
        print(f"Client archive created: {client_tar_path.name} ({client_tar_path.stat().st_size} bytes)")

        print("Uploading distribution and verified client snapshot to VPS...")
        scp_args = [
            SCP_COMMAND,
            "-i", SSH_KEY,
            "-o", "StrictHostKeyChecking=no",
            str(tar_path),
            str(client_tar_path),
            str(sync_script),
            f"{VPS_USER}@{VPS_HOST}:/tmp/"
        ]
        subprocess.run(scp_args, check=True)
        print("Archive uploaded successfully.")

    print("Atomically replacing VPS distribution and server client manifest...")
    remote_cmds = (
        f"sudo rm -rf {VPS_DEST_DIR}.next && "
        f"sudo mkdir -p {VPS_DEST_DIR}.next && "
        f"sudo tar -xzf /tmp/dist.tar.gz -C {VPS_DEST_DIR}.next && "
        f"sudo chown -R root:root {VPS_DEST_DIR}.next && "
        f"sudo chmod -R 755 {VPS_DEST_DIR}.next && "
        f"sudo rm -rf {VPS_DEST_DIR}.previous && "
        f"if sudo test -d {VPS_DEST_DIR}; then sudo mv {VPS_DEST_DIR} {VPS_DEST_DIR}.previous; fi && "
        f"sudo mv {VPS_DEST_DIR}.next {VPS_DEST_DIR} && "
        f"sudo bash /tmp/sync_client_manifest_atomic.sh /tmp/client.tar.gz {client_sha256} /opt/eclipse-rp && "
        f"rm -f /tmp/dist.tar.gz /tmp/sync_client_manifest_atomic.sh"
    )
    ssh_args = [
        SSH_COMMAND,
        "-i", SSH_KEY,
        "-o", "StrictHostKeyChecking=no",
        f"{VPS_USER}@{VPS_HOST}",
        remote_cmds
    ]
    subprocess.run(ssh_args, check=True)
    print("VPS distribution update complete!")

if __name__ == "__main__":
    main()
