#!/usr/bin/env bash
set -Eeuo pipefail

ARCHIVE="${1:?Usage: sync_client_manifest_atomic.sh ARCHIVE EXPECTED_SHA256 [SERVER_ROOT]}"
EXPECTED_SHA256="${2:?Expected SHA-256 is required}"
SERVER_ROOT="${3:-/opt/eclipse-rp}"
CLIENT_PATH="$SERVER_ROOT/plugins/RPChat/client"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
PREVIOUS_PATH="$SERVER_ROOT/plugins/RPChat/client.pre-sync-$STAMP"
BACKUP_PATH="$SERVER_ROOT/backups/client-pre-sync-$STAMP.tar.gz"
STAGING="$(mktemp -d "$SERVER_ROOT/.client-sync.XXXXXX")"
SWAPPED=0
SUCCESS=0

log() { printf '[client-sync] %s\n' "$*"; }

rollback() {
  local exit_code=$?
  if [[ "$SUCCESS" -eq 0 && "$SWAPPED" -eq 1 ]]; then
    log "Sync failed; restoring previous client directory"
    if [[ -d "$CLIENT_PATH" ]]; then mv -- "$CLIENT_PATH" "$CLIENT_PATH.failed-$STAMP" || true; fi
    if [[ -d "$PREVIOUS_PATH" ]]; then mv -- "$PREVIOUS_PATH" "$CLIENT_PATH" || true; fi
  fi
  rm -rf -- "$STAGING" || true
  exit "$exit_code"
}
trap rollback ERR INT TERM

if [[ "$(id -u)" -ne 0 ]]; then log "This script must run as root"; exit 1; fi
if [[ ! -f "$ARCHIVE" || ! -d "$CLIENT_PATH" ]]; then log "Archive or live client directory is missing"; exit 1; fi

ACTUAL_SHA256="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
if [[ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]]; then
  log "Archive checksum mismatch: $ACTUAL_SHA256"
  exit 1
fi

tar -xzf "$ARCHIVE" -C "$STAGING"
NEW_CLIENT="$STAGING/client"
if [[ ! -f "$NEW_CLIENT/mods.json" ]]; then log "Staged mods.json is missing"; exit 1; fi

python3 - "$NEW_CLIENT" <<'PY'
import hashlib
import json
import os
import pathlib
import sys

root = pathlib.Path(sys.argv[1]).resolve()
mods = json.loads((root / "mods.json").read_text(encoding="utf-8"))
if not isinstance(mods, list) or not mods:
    raise SystemExit("mods.json must be a non-empty array")
for descriptor in mods:
    relative = pathlib.Path(str(descriptor["path"]).replace("\\", "/"))
    candidate = (root / relative).resolve()
    if os.path.commonpath((str(root), str(candidate))) != str(root) or not candidate.is_file():
        raise SystemExit(f"Unsafe or missing manifest file: {relative}")
    if candidate.stat().st_size != int(descriptor["size"]):
        raise SystemExit(f"Size mismatch: {relative}")
    digest = hashlib.sha1(candidate.read_bytes()).hexdigest()
    if digest != str(descriptor["sha1"]).lower():
        raise SystemExit(f"SHA-1 mismatch: {relative}")
print(f"verified {len(mods)} managed mods")
PY

mkdir -p -- "$SERVER_ROOT/backups"
tar -C "$SERVER_ROOT/plugins/RPChat" -czf "$BACKUP_PATH" client
test -s "$BACKUP_PATH"

mv -- "$CLIENT_PATH" "$PREVIOUS_PATH"
mv -- "$NEW_CLIENT" "$CLIENT_PATH"
SWAPPED=1
chown -R minecraft:minecraft "$CLIENT_PATH"
find "$CLIENT_PATH" -type d -exec chmod 750 {} +
find "$CLIENT_PATH" -type f -exec chmod 640 {} +

SUCCESS=1
trap - ERR INT TERM
rm -rf -- "$STAGING"
rm -f -- "$ARCHIVE"
log "CLIENT_SYNC_OK backup=$BACKUP_PATH previous=$PREVIOUS_PATH"
