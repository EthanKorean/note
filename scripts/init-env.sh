#!/usr/bin/env bash
# note 의 설정과 비밀번호를 준비한다 ("init").
#  - .env : 비밀이 아닌 설정만 .env.example 에서 복사한다(이미 있으면 없는 키만 보충한다).
#  - 비밀번호(MariaDB root / 서비스 계정 / Redis): 숨김 입력으로 받아 DEK 로 암호화해 .secrets.enc 에 보관한다(scripts/lib/vault.sh).
#    평문으로는 어떤 파일에도 쓰지 않는다. run-backend.sh 와 docker/manage.sh 가 실행할 때만 복호화해 환경변수로 넘긴다.
#  - 예전 버전이 .env 에 평문으로 적어 둔 비밀번호가 있으면, 그 값을 그대로 보관함으로 옮기고 .env 에서 지운다.
#    (값은 이미 DB/Redis 컨테이너가 쓰고 있으므로 바꾸지 않는다. 그래서 이전 버전에서 git pull 한 뒤 이 스크립트를 한 번 실행하면 된다.)
#
#   scripts/init-env.sh            보관함에 없는 비밀번호만 입력받는다 (엔터만 치면 그 항목만 랜덤 값)
#   scripts/init-env.sh --random   묻지 않고 없는 비밀번호를 전부 랜덤으로 (사용자가 랜덤을 원할 때만)
#   scripts/init-env.sh --ask      보관된 값을 쓰지 않고 전부 새로 입력받아 덮어쓴다
#   환경변수 MARIADB_ROOT_PASSWORD / MARIADB_PASSWORD / REDIS_PASSWORD 가 있으면 입력 대신 그 값을 보관한다.
# 입력할 터미널이 없고 값도 없으면 임의로 만들지 않고 중단한다. 몇 번을 실행해도 안전하다(이미 있는 값은 건드리지 않는다).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
# shellcheck disable=SC1091
. ./scripts/lib/secrets.sh

MODE="ask"; ASK=0
for a in "$@"; do
  case "$a" in
    --random) MODE="random" ;;
    --ask)    ASK=1 ;;
    -h|--help) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "알 수 없는 옵션: $a (--random | --ask)" >&2; exit 1 ;;
  esac
done
log()  { printf '[init] %s\n' "$*"; }
warn() { printf '[경고] %s\n' "$*" >&2; }
rand() { openssl rand -hex 12; }   # 24자 (tr|head 는 pipefail 에서 SIGPIPE 로 죽는다)

# ---------- 1. .env (비밀 아닌 설정) ----------
# 이미 있는 키는 건드리지 않고, 없는 키만 .env.example 에서 보충한다.
if [[ -f .env ]]; then log ".env 이미 존재 - 누락된 키만 보충"; else log ".env 생성 (비밀번호는 넣지 않음)"; (umask 077; : > .env); fi
if [[ -s .env && -n "$(tail -c1 .env)" ]]; then echo >> .env; fi   # 마지막 줄 개행이 없으면 첫 추가 키가 그 줄에 붙어 깨진다
while IFS= read -r line || [[ -n "$line" ]]; do
  case "$line" in
    ""|\#*) continue ;;
    *=*) key="${line%%=*}"; val="${line#*=}" ;;
    *) continue ;;
  esac
  if LC_ALL=C grep -aEq "${ENV_LINE_PRE}${key}=" .env; then continue; fi
  printf '%s=%s\n' "$key" "$val" >> .env
done < .env.example
chmod 600 .env

# ---------- 2. 예전 방식의 평문 비밀번호 이전 ----------
env_value() { env_file_value .env "$1"; }   # .env 의 그 키 실제 값(scripts/lib/secrets.sh). 비어 있으면 출력 없음
strip_env_key() { # .env 에서 그 키의 줄을 지운다. 임시 파일에 쓴 뒤 바꿔치기한다(grep 이 줄이 없어서 1 로 끝나는 건 정상, 2 이상이면 .env 를 건드리지 않는다. 바이트 단위 -a 가 없으면 NUL 이 든 .env 는 "Binary file .env matches" 한 줄로 바뀐다)
  local tmp rc=0; tmp="$(umask 077; mktemp .env.XXXXXX)"
  LC_ALL=C grep -aEv "${ENV_LINE_PRE}${1}=" .env > "$tmp" || rc=$?
  if [[ "$rc" -gt 1 ]]; then rm -f "$tmp"; echo "[init] .env 에서 ${1} 줄을 지우지 못했습니다." >&2; return 1; fi
  mv -f "$tmp" .env
}
MIGRATED=""
if [[ "$ASK" == 0 ]]; then
  for k in $SECRET_KEYS; do
    v="$(env_value "$k")"
    if [[ -z "$v" ]]; then continue; fi
    if stored="$(vault_get "$k" 2>/dev/null)"; then
      # 이미 보관된 값이 있다. 같으면 .env 의 평문만 지우고, 다르면 어느 쪽이 맞는지 알 수 없으므로 건드리지 않는다.
      if [[ "$stored" == "$v" ]]; then strip_env_key "$k"; MIGRATED="$MIGRATED $k"
      else warn "$k: 보관함의 값과 .env 의 값이 다릅니다. DB/Redis 에 설정된 값이 어느 쪽인지 확인한 뒤 .env 의 해당 줄을 직접 지우세요."; fi
      continue
    fi
    if reason="$(secret_invalid_reason "$v" 72)"; then :; else warn "$k: .env 의 값이 규칙에 맞지 않아 옮기지 않았습니다 ($reason)"; continue; fi
    if ! vault_set "$k" "$v"; then warn "$k: 보관함에 저장하지 못했습니다. .env 는 그대로 둡니다."; continue; fi
    if [[ "$(vault_get "$k" 2>/dev/null)" != "$v" ]]; then warn "$k: 보관한 값을 다시 읽어 확인하지 못했습니다. .env 는 그대로 둡니다."; continue; fi
    strip_env_key "$k"; MIGRATED="$MIGRATED $k"
  done
  # JUSO_DB_PASSWORD 는 root 비밀번호와 같은 값이라 보관하지 않는다(실행할 때 채운다). 같은 값이면 평문을 지운다.
  v="$(env_value JUSO_DB_PASSWORD)"
  if [[ -n "$v" ]]; then
    root_pw="$(vault_get MARIADB_ROOT_PASSWORD 2>/dev/null || true)"
    if [[ "$v" == "$root_pw" ]]; then strip_env_key JUSO_DB_PASSWORD; MIGRATED="$MIGRATED JUSO_DB_PASSWORD"
    else warn "JUSO_DB_PASSWORD 가 MariaDB root 비밀번호와 다릅니다. .env 에 그대로 둡니다(환경변수로 우선 적용됩니다)."; fi
  fi
fi

# ---------- 3. 보관함에 없는 비밀번호 입력 ----------
label_of() {
  case "$1" in
    MARIADB_ROOT_PASSWORD) echo "MariaDB root 비밀번호" ;;
    MARIADB_PASSWORD)      echo "MariaDB 서비스 계정(note) 비밀번호" ;;
    REDIS_PASSWORD)        echo "Redis 비밀번호" ;;
  esac
}
ask_secret() { # <키> : 값을 stdout 으로. 환경변수 -> (--random 이면 랜덤) -> 터미널 입력 순
  local name="$1" label val b reason
  label="$(label_of "$name")"
  val="${!name:-}"
  if [[ -n "$val" ]]; then
    :
  elif [[ "$MODE" == random ]]; then
    val="$(rand)"
  else
    if [[ ! -t 0 ]]; then
      echo "$name 값이 없고 입력할 터미널도 없습니다. 터미널에서 scripts/init-env.sh 를 실행하거나 --random 을 쓰세요." >&2
      return 1
    fi
    while :; do
      read -r -s -p "  $label (${SECRET_MIN}자 이상, 엔터만 치면 랜덤): " val; echo >&2
      if [[ -z "$val" ]]; then val="$(rand)"; break; fi
      if reason="$(secret_invalid_reason "$val" 72)"; then :; else echo "  $reason" >&2; continue; fi
      read -r -s -p "  $label (확인): " b; echo >&2
      if [[ "$val" != "$b" ]]; then echo "  두 입력이 다릅니다. 다시 입력하세요." >&2; continue; fi
      break
    done
  fi
  if ! reason="$(secret_invalid_reason "$val" 72)"; then echo "$name: $reason" >&2; return 1; fi
  printf '%s' "$val"
}
ADDED=""; NEWKEYS=""
# 값을 전부 받은 뒤에 한꺼번에 저장한다. 하나씩 저장하면 --ask 중에 중단됐을 때(터미널 없음, Ctrl-C, 규칙 위반) 일부만 새 값으로 바뀌어 예전 값을 잃는다.
for k in $SECRET_KEYS; do
  if [[ "$ASK" == 0 ]] && vault_get "$k" >/dev/null 2>&1; then continue; fi
  # 위에서 옮기지 못한 평문(규칙 위반 등)이 .env 에 남아 있으면 새 값을 만들지 않는다. 새 값을 보관하면 run-backend.sh(.env 값 우선)와
  # docker/manage.sh(보관한 값 우선)가 서로 다른 비밀번호를 쓰게 되고, 이미 만든 DB/Redis 의 실제 비밀번호는 .env 쪽이다.
  if [[ "$ASK" == 0 && -n "$(env_value "$k")" ]]; then
    warn "$k: .env 의 예전 값을 옮기지 못해 새로 만들지 않습니다. 값을 규칙에 맞게 바꾸거나(DB/Redis 도 같은 값으로) .env 의 줄을 지운 뒤 다시 실행하세요."
    continue
  fi
  v="$(ask_secret "$k")" || exit 1
  printf -v "NEW_$k" '%s' "$v"
  NEWKEYS="$NEWKEYS $k"
done
for k in $NEWKEYS; do
  name="NEW_$k"
  vault_set "$k" "${!name}" || exit 1
  ADDED="$ADDED $k"
done

# ---------- 4. 결과 ----------
if [[ -z "$MIGRATED$ADDED" ]]; then
  MISSING=""
  for k in $SECRET_KEYS; do vault_get "$k" >/dev/null 2>&1 || MISSING="$MISSING $k"; done
  if [[ -z "$MISSING" ]]; then
    log "보관함에 비밀번호가 이미 모두 있습니다. 바꿀 것이 없습니다. (새로 입력하려면 scripts/init-env.sh --ask)"
  else
    warn "보관하지 못한 비밀번호가 있습니다:${MISSING} (위 경고를 확인하세요)"
  fi
else
  if [[ -n "$MIGRATED" ]]; then log "예전 방식의 평문 비밀번호를 암호화해 보관하고 .env 에서 지웠습니다:${MIGRATED}"; fi
  if [[ -n "$ADDED" ]]; then log "새로 보관한 비밀번호:${ADDED}"; fi
  log "암호문: ${VAULT_FILE}"
  log "DEK   : ${DEK_FILE}  (권한 600. 잃으면 암호문을 풀 수 없으니 지우지 마세요)"
fi
if [[ "$ASK" == 1 ]]; then
  for k in $SECRET_KEYS JUSO_DB_PASSWORD; do
    if [[ -n "$(env_value "$k")" ]]; then
      warn ".env 에 예전 평문 값이 남은 비밀번호는 run-backend.sh 가 그 값을 우선 씁니다(.env 가 보관함보다 먼저 적용됨). 새 값을 쓰려면 .env 의 해당 줄을 지우세요."
      break
    fi
  done
  warn "이미 만든 MariaDB/Redis 볼륨은 예전 비밀번호를 기억합니다. 바뀐 값을 쓰려면 docker/manage.sh destroy mariadb|redis 로 지운 뒤 다시 up 하세요."
fi
warn_plaintext_env .env
