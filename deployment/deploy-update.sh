#!/usr/bin/env bash
set -euo pipefail

SERVER_DIR=/opt/eclipse-rp
UPDATE_ARCHIVE=/tmp/eclipse-server-update.tar.gz
SUPERSEDED_CLIENT_SHA1=d2a7c3b26354307f9a6d504f16aae5a786a11b7f

if [[ ! -f "$UPDATE_ARCHIVE" ]]; then
  echo "Missing $UPDATE_ARCHIVE" >&2
  exit 1
fi

sudo systemctl stop eclipse-rp
stamp="$(date -u +%Y%m%d-%H%M%S)"
sudo install -d -o root -g root -m 0750 "$SERVER_DIR/backups"

sudo tar -C "$SERVER_DIR" -czf "$SERVER_DIR/backups/before-eclipse-brand-$stamp.tar.gz" \
  plugins/RPChat.jar \
  plugins/RPChat/config.yml \
  plugins/RPChat/client \
  plugins/RPChat/web
sudo tar -xzf "$UPDATE_ARCHIVE" -C "$SERVER_DIR"

while IFS= read -r -d '' candidate; do
  if [[ "$(sudo sha1sum "$candidate" | cut -d' ' -f1)" == "$SUPERSEDED_CLIENT_SHA1" ]]; then
    sudo rm -f -- "$candidate"
  fi
done < <(sudo find "$SERVER_DIR/plugins/RPChat/client/mods" -maxdepth 1 -type f -name '*.jar' -print0)

sudo chown -R minecraft:minecraft "$SERVER_DIR/plugins"
sudo systemctl start eclipse-rp
