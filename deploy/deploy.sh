#!/usr/bin/env bash
# Выкатить текущую версию сервиса на сервер и перезапустить его.
#   ./deploy/deploy.sh                  # сервер по умолчанию
#   VOLNA_SERVER=root@host ./deploy/deploy.sh
set -euo pipefail

SERVER="${VOLNA_SERVER:-root@de5.kairavpn.pro}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "Копирую сервис на $SERVER…"
COPYFILE_DISABLE=1 tar -C "$ROOT" -cz --no-xattrs --no-mac-metadata \
  --exclude .venv --exclude data --exclude .env --exclude .pytest_cache --exclude __pycache__ --exclude '*.egg-info' \
  wave-service -C "$ROOT/deploy" docker-compose.yml |
  ssh "$SERVER" 'set -e; mkdir -p /opt/volna && cd /opt/volna && rm -rf wave-service && tar -xzf - --no-same-owner'

echo "Собираю и перезапускаю…"
ssh "$SERVER" 'set -e; cd /opt/volna && mkdir -p data && chown 1000:1000 data && docker compose up -d --build && docker image prune -f >/dev/null'
echo "Готово: https://${SERVER#*@}/volna/"
