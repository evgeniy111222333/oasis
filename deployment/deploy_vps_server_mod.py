#!/usr/bin/env python3
"""Atomically deploy the Fabric server mod to production with readiness rollback."""

from __future__ import annotations

import hashlib
import os
import shlex
import subprocess
from pathlib import Path


SSH_KEY = Path(os.environ.get("ECLIPSE_VPS_SSH_KEY", r"E:\eclipse-stock.pem"))
VPS_HOST = os.environ.get("ECLIPSE_VPS_HOST", "13.51.232.191")
VPS_USER = os.environ.get("ECLIPSE_VPS_USER", "ubuntu")
SERVER_ROOT = "/opt/eclipse-rp"
LIVE_JAR = f"{SERVER_ROOT}/mods/eclipseserver-1.4.5.jar"
WINDOWS_OPENSSH = Path(os.environ.get("WINDIR", r"C:\Windows")) / "System32" / "OpenSSH"
SCP_COMMAND = str(WINDOWS_OPENSSH / "scp.exe") if (WINDOWS_OPENSSH / "scp.exe").is_file() else "scp"
SSH_COMMAND = str(WINDOWS_OPENSSH / "ssh.exe") if (WINDOWS_OPENSSH / "ssh.exe").is_file() else "ssh"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def ssh_base() -> list[str]:
    return [
        SSH_COMMAND,
        "-i", str(SSH_KEY),
        "-o", "StrictHostKeyChecking=no",
        "-o", "ConnectTimeout=15",
        f"{VPS_USER}@{VPS_HOST}",
    ]


def current_remote_sha256() -> str:
    result = subprocess.run(
        ssh_base() + [f"sudo sha256sum {shlex.quote(LIVE_JAR)}"],
        check=True,
        capture_output=True,
        text=True,
    )
    fields = result.stdout.strip().split()
    if len(fields) < 2 or len(fields[0]) != 64:
        raise RuntimeError(f"Unexpected production server checksum response: {result.stdout!r}")
    return fields[0].lower()


def remote_deploy_script() -> str:
    return r'''set -Eeuo pipefail

UPLOAD_PATH="${1:?upload path required}"
EXPECTED_SHA256="${2:?sha256 required}"
SERVER_ROOT="/opt/eclipse-rp"
LIVE_JAR="$SERVER_ROOT/mods/eclipseserver-1.4.5.jar"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP_DIR="$SERVER_ROOT/backups/server-mod-pre-publish-$STAMP"
START_EPOCH="$(date +%s)"
SERVER_STOPPED=0
REPLACED=0
SUCCESS=0

rollback() {
  local exit_code=$?
  trap - ERR INT TERM
  set +e
  if [[ "$SUCCESS" -eq 0 && "$SERVER_STOPPED" -eq 1 ]]; then
    echo "[server-publish] deployment failed; restoring previous server mod"
    systemctl stop eclipse-rp
    if [[ "$REPLACED" -eq 1 && -f "$BACKUP_DIR/eclipseserver-1.4.5.jar" ]]; then
      install -o minecraft -g minecraft -m 0640 \
        "$BACKUP_DIR/eclipseserver-1.4.5.jar" "$LIVE_JAR"
    fi
    systemctl start eclipse-rp
  fi
  rm -f "$UPLOAD_PATH"
  exit "$exit_code"
}
trap rollback ERR INT TERM

[[ "$(id -u)" -eq 0 ]]
[[ -f "$UPLOAD_PATH" && -f "$LIVE_JAR" ]]
[[ "$(sha256sum "$UPLOAD_PATH" | awk '{print $1}')" == "$EXPECTED_SHA256" ]]

CURRENT_SHA256="$(sha256sum "$LIVE_JAR" | awk '{print $1}')"
if [[ "$CURRENT_SHA256" == "$EXPECTED_SHA256" ]]; then
  SUCCESS=1
  trap - ERR INT TERM
  rm -f "$UPLOAD_PATH"
  echo "[server-publish] SERVER_MOD_ALREADY_CURRENT sha256=$EXPECTED_SHA256"
  exit 0
fi

mkdir -p "$BACKUP_DIR"
cp -a "$LIVE_JAR" "$BACKUP_DIR/eclipseserver-1.4.5.jar"
printf '%s  %s\n' "$CURRENT_SHA256" "eclipseserver-1.4.5.jar" \
  > "$BACKUP_DIR/eclipseserver-1.4.5.jar.sha256"

systemctl stop eclipse-rp
SERVER_STOPPED=1
if systemctl is-active --quiet eclipse-rp; then
  echo "[server-publish] service did not stop"
  false
fi

install -o minecraft -g minecraft -m 0640 "$UPLOAD_PATH" "$LIVE_JAR"
REPLACED=1
[[ "$(sha256sum "$LIVE_JAR" | awk '{print $1}')" == "$EXPECTED_SHA256" ]]
systemctl start eclipse-rp

for _ in $(seq 1 120); do
  if ! systemctl is-active --quiet eclipse-rp; then
    journalctl -u eclipse-rp --since "@$START_EPOCH" --no-pager -n 200
    false
  fi
  JOURNAL="$(journalctl -u eclipse-rp --since "@$START_EPOCH" --no-pager)"
  if grep -q 'Done (' <<<"$JOURNAL"; then
    if grep -Eq 'Mixin apply failed|InvalidAccessorException|Critical injection failure|FATAL' <<<"$JOURNAL"; then
      printf '%s\n' "$JOURNAL"
      false
    fi
    [[ "$(sha256sum "$LIVE_JAR" | awk '{print $1}')" == "$EXPECTED_SHA256" ]]
    ss -ltn | grep -q ':25565[[:space:]]'
    ss -ltn | grep -q ':25580[[:space:]]'
    SUCCESS=1
    trap - ERR INT TERM
    rm -f "$UPLOAD_PATH"
    echo "[server-publish] SERVER_MOD_DEPLOY_OK backup=$BACKUP_DIR sha256=$EXPECTED_SHA256"
    exit 0
  fi
  sleep 1
done

journalctl -u eclipse-rp --since "@$START_EPOCH" --no-pager -n 200
echo "[server-publish] readiness timeout"
false
'''


def main() -> None:
    repo = Path(__file__).resolve().parents[1]
    server_jar = repo / "fabric-server" / "mods" / "eclipseserver-1.4.5.jar"
    if not server_jar.is_file():
        raise FileNotFoundError(f"Built Fabric server mod is missing: {server_jar}")
    if not SSH_KEY.is_file():
        raise FileNotFoundError(f"VPS SSH key is missing: {SSH_KEY}")

    expected = sha256(server_jar)
    current = current_remote_sha256()
    if current == expected:
        print(f"SERVER_MOD_ALREADY_CURRENT sha256={expected}")
        return

    remote_upload = f"/tmp/eclipseserver-1.4.5.{expected[:12]}.upload.jar"
    target = f"{VPS_USER}@{VPS_HOST}:{remote_upload}"
    print(f"Uploading Fabric server mod {current} -> {expected}...")
    try:
        subprocess.run([
            SCP_COMMAND,
            "-i", str(SSH_KEY),
            "-o", "StrictHostKeyChecking=no",
            str(server_jar), target,
        ], check=True)
        subprocess.run(
            ssh_base() + [f"sudo bash -s -- {shlex.quote(remote_upload)} {expected}"],
            # Feed raw LF-delimited bytes. On Windows, a text-mode stdin pipe can
            # translate them to CRLF, which makes the remote Bash parser reject
            # function declarations before the guarded deployment even starts.
            input=remote_deploy_script().encode("utf-8"),
            check=True,
        )
    except BaseException:
        subprocess.run(ssh_base() + [f"rm -f {shlex.quote(remote_upload)}"], check=False)
        raise

    deployed = current_remote_sha256()
    if deployed != expected:
        raise RuntimeError(f"Production server checksum mismatch after deploy: {deployed}")
    print(f"SERVER_MOD_PUBLISH_OK sha256={deployed}")


if __name__ == "__main__":
    main()
