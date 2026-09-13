#!/usr/bin/env bash
set -Eeuo pipefail

ARCHIVE="${1:?Usage: migrate_to_fabric.sh ARCHIVE EXPECTED_SHA256}"
EXPECTED_SHA256="${2:?Expected SHA-256 is required}"
SERVER_ROOT="/opt/eclipse-rp"
SERVICE_FILE="/etc/systemd/system/eclipse-rp.service"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP_ROOT="$SERVER_ROOT/backups/pre-fabric-$STAMP"
STAGING="$(mktemp -d "$SERVER_ROOT/.fabric-stage.XXXXXX")"
START_EPOCH="$(date +%s)"
SERVICE_REPLACED=0
MODS_REPLACED=0
SERVER_STOPPED=0
SUCCESS=0

log() { printf '[fabric-migration] %s\n' "$*"; }

rollback() {
  local exit_code=$?
  if [[ "$SUCCESS" -eq 0 && "$SERVER_STOPPED" -eq 1 ]]; then
    log "Migration failed; restoring the Purpur service"
    systemctl stop eclipse-rp || true
    if [[ "$MODS_REPLACED" -eq 1 && -d "$SERVER_ROOT/mods" ]]; then
      mv -- "$SERVER_ROOT/mods" "$BACKUP_ROOT/failed-fabric-mods" || true
    fi
    if [[ -d "$BACKUP_ROOT/previous-mods" ]]; then
      mv -- "$BACKUP_ROOT/previous-mods" "$SERVER_ROOT/mods" || true
    fi
    if [[ "$SERVICE_REPLACED" -eq 1 ]]; then
      cp -a -- "$BACKUP_ROOT/eclipse-rp.service" "$SERVICE_FILE" || true
    fi
    systemctl daemon-reload || true
    systemctl start eclipse-rp || true
  fi
  rm -rf -- "$STAGING" || true
  exit "$exit_code"
}
trap rollback ERR INT TERM

if [[ "$(id -u)" -ne 0 ]]; then log "This script must run as root"; exit 1; fi
if [[ ! -f "$ARCHIVE" ]]; then log "Archive is missing: $ARCHIVE"; exit 1; fi
ACTUAL_SHA256="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
if [[ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]]; then
  log "Archive checksum mismatch: $ACTUAL_SHA256"
  exit 1
fi

tar -xzf "$ARCHIVE" -C "$STAGING"
for required in fabric-server-launch.jar fabric-server-launcher.properties minecraft-server-26.1.2.jar \
  mods/eclipseserver-1.4.5.jar mods/fabric-api.jar mods/Axiom-5.4.2-for-MC26.1.jar \
  libraries/net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar eclipse-rp-fabric.service; do
  if [[ ! -f "$STAGING/$required" ]]; then log "Staged file is missing: $required"; exit 1; fi
done

mkdir -p -- "$BACKUP_ROOT"
cp -a -- "$SERVICE_FILE" "$BACKUP_ROOT/eclipse-rp.service"
log "Stopping Purpur and checkpointing live state"
systemctl stop eclipse-rp
SERVER_STOPPED=1
if systemctl is-active --quiet eclipse-rp; then log "Server did not stop"; exit 1; fi

log "Creating full pre-Fabric backup"
tar -C "$SERVER_ROOT" -czf "$BACKUP_ROOT/eclipse-rp-state.tar.gz" \
  world plugins config server.jar server.properties ops.json whitelist.json banned-ips.json banned-players.json \
  usercache.json permissions.yml 2>/dev/null
test -s "$BACKUP_ROOT/eclipse-rp-state.tar.gz"

mkdir -p -- "$SERVER_ROOT/config/RPChat"
tar -C "$SERVER_ROOT/plugins/RPChat" --exclude='./client.pre-sync-*' -cf - . \
  | tar -C "$SERVER_ROOT/config/RPChat" -xf -
printf 'Migrated from plugins/RPChat; legacy source preserved.\n' \
  > "$SERVER_ROOT/config/RPChat/.legacy-migration-complete"
if ! grep -Eq '^combat:[[:space:]]*$' "$SERVER_ROOT/config/RPChat/config.yml"; then
  printf '\n# Roleplay combat can be disabled at runtime with /rpreload.\ncombat:\n  enabled: true\n' \
    >> "$SERVER_ROOT/config/RPChat/config.yml"
fi

if [[ -d "$SERVER_ROOT/mods" ]]; then mv -- "$SERVER_ROOT/mods" "$BACKUP_ROOT/previous-mods"; fi
cp -a -- "$STAGING/mods" "$SERVER_ROOT/mods"
MODS_REPLACED=1
mkdir -p -- "$SERVER_ROOT/libraries"
cp -a -- "$STAGING/libraries/." "$SERVER_ROOT/libraries/"
install -o minecraft -g minecraft -m 0640 "$STAGING/fabric-server-launch.jar" "$SERVER_ROOT/fabric-server-launch.jar"
install -o minecraft -g minecraft -m 0640 "$STAGING/fabric-server-launcher.properties" "$SERVER_ROOT/fabric-server-launcher.properties"
install -o minecraft -g minecraft -m 0640 "$STAGING/minecraft-server-26.1.2.jar" "$SERVER_ROOT/minecraft-server-26.1.2.jar"
chown -R minecraft:minecraft "$SERVER_ROOT/mods" "$SERVER_ROOT/libraries" "$SERVER_ROOT/config/RPChat"

install -o root -g root -m 0644 "$STAGING/eclipse-rp-fabric.service" "$SERVICE_FILE"
SERVICE_REPLACED=1
systemctl daemon-reload
systemctl start eclipse-rp

log "Waiting for Fabric readiness"
for _ in $(seq 1 120); do
  if ! systemctl is-active --quiet eclipse-rp; then
    journalctl -u eclipse-rp --since "@$START_EPOCH" --no-pager -n 160
    false
  fi
  JOURNAL="$(journalctl -u eclipse-rp --since "@$START_EPOCH" --no-pager)"
  if grep -q 'Done (' <<<"$JOURNAL"; then
    grep -q 'Fabric Loader' <<<"$JOURNAL"
    grep -q 'eclipseserver 1.4.5' <<<"$JOURNAL"
    ss -ltn | grep -q ':25565[[:space:]]'
    ss -ltn | grep -q ':25580[[:space:]]'
    SUCCESS=1
    trap - ERR INT TERM
    rm -rf -- "$STAGING"
    rm -f -- "$ARCHIVE"
    log "FABRIC_MIGRATION_OK backup=$BACKUP_ROOT"
    exit 0
  fi
  sleep 1
done

journalctl -u eclipse-rp --since "@$START_EPOCH" --no-pager -n 200
log "Fabric readiness timeout"
false
