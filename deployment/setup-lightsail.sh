#!/usr/bin/env bash
set -euo pipefail

ARCHIVE=/tmp/eclipse-rp-runtime.tar.gz
SERVER_DIR=/opt/eclipse-rp

if [[ ! -f "$ARCHIVE" ]]; then
  echo "Missing $ARCHIVE" >&2
  exit 1
fi

if ! id minecraft >/dev/null 2>&1; then
  sudo useradd --system --create-home --home-dir /home/minecraft --shell /usr/sbin/nologin minecraft
fi

sudo install -d -o minecraft -g minecraft -m 0750 "$SERVER_DIR"
sudo tar -xzf "$ARCHIVE" -C "$SERVER_DIR"
sudo chown -R minecraft:minecraft "$SERVER_DIR"

if [[ ! -f /swapfile ]]; then
  sudo fallocate -l 2G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
fi
if ! sudo swapon --show=NAME --noheadings | grep -qx /swapfile; then
  sudo swapon /swapfile
fi
if ! grep -q '^/swapfile ' /etc/fstab; then
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
fi
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-eclipse-rp.conf >/dev/null
sudo sysctl -q -p /etc/sysctl.d/99-eclipse-rp.conf

sudo apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y caddy ufw

sudo install -o root -g root -m 0644 /tmp/eclipse-rp.service /etc/systemd/system/eclipse-rp.service
sudo install -o root -g root -m 0644 /tmp/Caddyfile /etc/caddy/Caddyfile

sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH
sudo ufw allow 25565/tcp comment 'Minecraft'
sudo ufw allow 80/tcp comment 'HTTP certificate redirect'
sudo ufw allow 443/tcp comment 'HTTPS launcher API'
sudo ufw --force enable

sudo systemctl daemon-reload
sudo systemctl enable eclipse-rp caddy
sudo systemctl restart eclipse-rp
sudo systemctl restart caddy

echo 'Host setup complete.'
