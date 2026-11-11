#!/usr/bin/env bash
# 루트의 .env 를 .env.example 에서 만든다. 이미 있으면 건드리지 않는다.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -f "$ROOT/.env" ]]; then
  echo ".env 가 이미 있습니다: $ROOT/.env"
  exit 0
fi
cp "$ROOT/.env.example" "$ROOT/.env"
chmod 600 "$ROOT/.env"
echo "$ROOT/.env 를 만들었습니다. 비밀번호(MARIADB_ROOT_PASSWORD, MARIADB_PASSWORD, JUSO_DB_PASSWORD)를 채우세요."
echo "  JUSO_DB_PASSWORD 는 MARIADB_ROOT_PASSWORD 와 같은 값입니다."
