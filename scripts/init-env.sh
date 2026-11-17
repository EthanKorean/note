#!/usr/bin/env bash
# 루트의 .env 를 .env.example 에서 만든다. 이미 있으면 건드리지 않는다.
# 비밀번호는 사용자가 정한다. (JUSO_DB_PASSWORD 는 MARIADB_ROOT_PASSWORD 와 같은 값으로 채운다)
#
#   scripts/init-env.sh            터미널에서 직접 입력 (엔터만 치면 그 항목만 랜덤 값)
#   scripts/init-env.sh --random   묻지 않고 전부 랜덤 값 (사용자가 랜덤을 원할 때만)
#   환경변수 MARIADB_ROOT_PASSWORD / MARIADB_PASSWORD / REDIS_PASSWORD 가 있으면 그 값을 쓴다.
# 입력할 터미널이 없고 값도 없으면 임의로 만들지 않고 중단한다.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -f "$ROOT/.env" ]]; then
  echo ".env 가 이미 있습니다: $ROOT/.env"
  exit 0
fi

MODE="ask"
[[ "${1:-}" == "--random" ]] && MODE="random"
rand() { openssl rand -hex 12; }   # 24자 (tr|head 는 pipefail 에서 SIGPIPE 로 죽는다)

# ask_pw <변수명> <설명>: 환경변수 → (--random 이면 랜덤) → 터미널 입력 순으로 값을 정한다.
ask_pw() {
  local name="$1" label="$2" val="${!1:-}"
  if [[ -z "$val" && "$MODE" == "random" ]]; then
    val="$(rand)"
  elif [[ -z "$val" ]]; then
    if [[ ! -t 0 ]]; then
      echo "$name 값이 없고 입력할 터미널도 없습니다. 터미널에서 scripts/init-env.sh 를 실행하거나 --random 을 쓰세요." >&2
      exit 1
    fi
    read -r -s -p "$label (엔터만 치면 랜덤): " val; echo >&2
    [[ -n "$val" ]] || val="$(rand)"
  fi
  # .env 는 bash(source)와 docker compose 가 읽는다. $ 공백 & 따옴표 같은 문자가 있으면 깨지므로 막는다.
  if [[ ! "$val" =~ ^[A-Za-z0-9_.@%+-]+$ ]]; then
    echo "$name: 비밀번호는 영문, 숫자와 _ . @ % + - 만 쓸 수 있습니다." >&2
    exit 1
  fi
  printf '%s' "$val"
}

ROOT_PW="$(ask_pw MARIADB_ROOT_PASSWORD 'MariaDB root 비밀번호')"
APP_PW="$(ask_pw MARIADB_PASSWORD 'MariaDB note 계정 비밀번호')"
REDIS_PW="$(ask_pw REDIS_PASSWORD 'Redis 비밀번호')"

cp "$ROOT/.env.example" "$ROOT/.env"
chmod 600 "$ROOT/.env"
# '=' 뒤가 비어 있는 줄만 채운다. sed 의 치환 문자(& | \)에 영향받지 않도록 awk + ENVIRON 을 쓴다.
fill() { # fill <키> <값>
  K="$1" V="$2" awk 'BEGIN{FS=OFS="="} $1==ENVIRON["K"] && $2=="" && NF==2 {print $1 "=" ENVIRON["V"]; next} {print}' \
    "$ROOT/.env" > "$ROOT/.env.tmp" && mv "$ROOT/.env.tmp" "$ROOT/.env"
}
fill MARIADB_ROOT_PASSWORD "$ROOT_PW"
fill JUSO_DB_PASSWORD "$ROOT_PW"
fill MARIADB_PASSWORD "$APP_PW"
fill REDIS_PASSWORD "$REDIS_PW"
chmod 600 "$ROOT/.env"
echo "$ROOT/.env 를 만들었습니다. (권한 600, 커밋되지 않음)"
