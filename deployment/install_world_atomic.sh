#!/usr/bin/env bash
set -Eeuo pipefail

# Atomically replaces a Minecraft world while keeping both a compressed backup
# and the previous world directory. Must be run as root on the game host.

ARCHIVE="${1:?Usage: install_world_atomic.sh ARCHIVE EXPECTED_SHA256 [SERVER_ROOT] [SERVICE]}"
EXPECTED_SHA256="${2:?Expected SHA-256 is required}"
SERVER_ROOT="${3:-/opt/eclipse-rp}"
SERVICE="${4:-eclipse-rp}"
WORLD_NAME="world"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
WORLD_PATH="$SERVER_ROOT/$WORLD_NAME"
PREVIOUS_PATH="$SERVER_ROOT/${WORLD_NAME}.pre-eclipse-map-$STAMP"
FAILED_PATH="$SERVER_ROOT/${WORLD_NAME}.failed-$STAMP"
BACKUP_PATH="$SERVER_ROOT/backups/${WORLD_NAME}-pre-eclipse-map-$STAMP.tar.gz"
STAGING_ROOT="$(mktemp -d "$SERVER_ROOT/.world-import.XXXXXX")"
SWAPPED=0
SUCCESS=0

log() {
  printf '[world-install] %s\n' "$*"
}

rollback() {
  local exit_code=$?
  if [[ "$SUCCESS" -eq 0 && "$SWAPPED" -eq 1 ]]; then
    log "Installation failed; restoring the previous world"
    systemctl stop "$SERVICE" || true
    if [[ -d "$WORLD_PATH" ]]; then
      mv -- "$WORLD_PATH" "$FAILED_PATH" || true
    fi
    if [[ -d "$PREVIOUS_PATH" ]]; then
      mv -- "$PREVIOUS_PATH" "$WORLD_PATH" || true
    fi
    systemctl start "$SERVICE" || true
  fi
  rm -rf -- "$STAGING_ROOT" || true
  exit "$exit_code"
}
trap rollback ERR INT TERM

if [[ "$(id -u)" -ne 0 ]]; then
  log "This script must run as root"
  exit 1
fi
if [[ ! -f "$ARCHIVE" || ! -d "$SERVER_ROOT" || ! -d "$WORLD_PATH" ]]; then
  log "Archive, server root, or current world is missing"
  exit 1
fi

ACTUAL_SHA256="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
if [[ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]]; then
  log "Archive checksum mismatch: $ACTUAL_SHA256"
  exit 1
fi

mkdir -p -- "$SERVER_ROOT/backups"
log "Stopping $SERVICE"
systemctl stop "$SERVICE"
for _ in $(seq 1 30); do
  if ! systemctl is-active --quiet "$SERVICE"; then
    break
  fi
  sleep 1
done
if systemctl is-active --quiet "$SERVICE"; then
  log "Service did not stop in time"
  exit 1
fi

log "Creating compressed backup $BACKUP_PATH"
tar -C "$SERVER_ROOT" -czf "$BACKUP_PATH" "$WORLD_NAME"
test -s "$BACKUP_PATH"

log "Extracting and validating the uploaded world"
python3 - "$ARCHIVE" "$STAGING_ROOT" <<'PY'
import os
import pathlib
import sys
import zipfile

archive = pathlib.Path(sys.argv[1]).resolve()
destination = pathlib.Path(sys.argv[2]).resolve()
with zipfile.ZipFile(archive) as source:
    for entry in source.infolist():
        target = (destination / entry.filename).resolve()
        if os.path.commonpath((str(destination), str(target))) != str(destination):
            raise SystemExit(f"Unsafe ZIP member: {entry.filename}")
    source.extractall(destination)
PY

mapfile -t ROOTS < <(find "$STAGING_ROOT" -mindepth 1 -maxdepth 1 -type d -print)
if [[ "${#ROOTS[@]}" -ne 1 || ! -f "${ROOTS[0]}/level.dat" ]]; then
  log "The archive must contain exactly one world directory with level.dat"
  exit 1
fi
NEW_WORLD="${ROOTS[0]}"
rm -f -- "$NEW_WORLD/session.lock"

log "Swapping worlds atomically"
mv -- "$WORLD_PATH" "$PREVIOUS_PATH"
mv -- "$NEW_WORLD" "$WORLD_PATH"
SWAPPED=1
chown -R minecraft:minecraft "$WORLD_PATH"
find "$WORLD_PATH" -type d -exec chmod 770 {} +
find "$WORLD_PATH" -type f -exec chmod 660 {} +

log "Starting $SERVICE and waiting for port 25565"
systemctl start "$SERVICE"
READY=0
for _ in $(seq 1 120); do
  if ! systemctl is-active --quiet "$SERVICE"; then
    log "Service stopped during startup"
    exit 1
  fi
  if ss -ltn | grep -qE '[:.]25565[[:space:]]'; then
    READY=1
    break
  fi
  sleep 1
done
if [[ "$READY" -ne 1 ]]; then
  log "Server did not open port 25565 in time"
  exit 1
fi

SUCCESS=1
trap - ERR INT TERM
rm -rf -- "$STAGING_ROOT"
rm -f -- "$ARCHIVE"
log "WORLD_INSTALL_OK backup=$BACKUP_PATH previous=$PREVIOUS_PATH"
systemctl --no-pager --full status "$SERVICE" | sed -n '1,12p'
