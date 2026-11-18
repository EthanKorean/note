#!/usr/bin/env bash
# 입력한 비밀번호를 DEK(데이터 암호화 키)로 암호화해 보관하는 모듈. init-env.sh, vault.sh, run-backend.sh 등이 source 한다.
#
#  - 암호문: 프로젝트 폴더의 .secrets.enc (git 제외). 폴더를 통째로 공유하거나 올려도 암호문만 나간다.
#  - DEK   : 저장소 밖 ~/.config/note/dek.key (권한 600). 처음 쓸 때 openssl 로 랜덤 생성한다.
#  - 알고리즘: AES-256-CBC + PBKDF2(200000회, salt). 평문 첫 줄의 매직 문자열로 복호화 성공 여부(키가 맞는지)를 확인한다.
#  - 평문 형식: KEY=VALUE 한 줄씩. 키 이름은 MARIADB_PASSWORD 처럼 대문자/숫자/_ 만.
#
# 한계(감수한 것): DEK 를 읽을 수 있는 사람은 비밀번호를 읽을 수 있다. DEK 가 노출되면 소용없다.
# 로컬 개발용 비밀번호에만 쓰고, 운영 비밀은 넣지 않는다. macOS 기본 bash 3.2 호환.
#
# 환경변수로 위치를 바꿀 수 있다: NOTE_VAULT_FILE(암호문), NOTE_DEK_FILE(DEK). 테스트에서 쓴다.

VAULT_MAGIC="HRVAULT1"
VAULT_FILE="${NOTE_VAULT_FILE:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/.secrets.enc}"
DEK_FILE="${NOTE_DEK_FILE:-${XDG_CONFIG_HOME:-$HOME/.config}/note/dek.key}"
_VAULT_OPTS="-aes-256-cbc -pbkdf2 -iter 200000"

vault_valid_key() { # 대문자로 시작하고 대문자/숫자/_ 만. 로케일에 따라 [A-Z] 가 소문자를 포함할 수 있어 문자를 직접 나열한다.
  case "$1" in
    ""|*[!ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_]*|[0123456789_]*) return 1 ;;
  esac
  return 0
}

# DEK 가 없으면 만든다. 암호문은 있는데 DEK 만 없으면 새로 만들어 봐야 풀 수 없으므로 멈춘다.
vault_dek_ensure() {
  if [ -f "$DEK_FILE" ]; then
    [ -s "$DEK_FILE" ] || { echo "[vault] DEK 파일이 비어 있습니다: $DEK_FILE" >&2; return 1; }
    return 0
  fi
  if [ -f "$VAULT_FILE" ]; then
    echo "[vault] 암호문(${VAULT_FILE})은 있는데 DEK(${DEK_FILE})가 없습니다. DEK 를 복구하거나 암호문을 지우고 다시 저장하세요." >&2
    return 1
  fi
  local dir
  dir="$(dirname "$DEK_FILE")"
  if [ ! -d "$dir" ]; then
    (umask 077; mkdir -p "$dir") || return 1
    chmod 700 "$dir"
  fi
  (umask 077; openssl rand -base64 32 > "$DEK_FILE") || { rm -f "$DEK_FILE"; return 1; }
  chmod 600 "$DEK_FILE"
}

# 복호화한 평문(매직 줄 제외)을 stdout 으로. 암호문이 없으면 아무것도 출력하지 않고 0.
_vault_plain() {
  [ -f "$VAULT_FILE" ] || return 0
  [ -f "$DEK_FILE" ] || { echo "[vault] DEK 가 없습니다: $DEK_FILE" >&2; return 1; }
  local out
  out="$(openssl enc -d $_VAULT_OPTS -pass "file:$DEK_FILE" -in "$VAULT_FILE" 2>/dev/null)" ||
    { echo "[vault] 복호화에 실패했습니다(DEK 가 다르거나 파일이 손상됨): $VAULT_FILE" >&2; return 1; }
  case "$out" in
    "$VAULT_MAGIC"*) ;;
    *) echo "[vault] 복호화 결과가 올바르지 않습니다(DEK 가 다름): $VAULT_FILE" >&2; return 1 ;;
  esac
  printf '%s\n' "$out" | sed 1d
}

# stdin 의 KEY=VALUE 줄들을 암호화해 저장한다(임시 파일에 쓴 뒤 바꿔치기해서 쓰다가 실패해도 기존 암호문이 안 깨진다).
_vault_write() {
  local body tmp
  body="$(cat)"
  vault_dek_ensure || return 1
  tmp="$(umask 077; mktemp "${VAULT_FILE}.XXXXXX")" || return 1
  if { printf '%s\n' "$VAULT_MAGIC"; if [ -n "$body" ]; then printf '%s\n' "$body"; fi; } |
      openssl enc $_VAULT_OPTS -salt -pass "file:$DEK_FILE" -out "$tmp"; then
    chmod 600 "$tmp"
    mv -f "$tmp" "$VAULT_FILE"
  else
    rm -f "$tmp"
    return 1
  fi
}

vault_set() { # KEY VALUE
  local k="$1" v="$2" cur
  vault_valid_key "$k" || { echo "[vault] 키 이름은 대문자로 시작하고 대문자/숫자/_ 만 쓸 수 있습니다: $k" >&2; return 1; }
  [ -n "$v" ] || { echo "[vault] 빈 값은 저장하지 않습니다: $k" >&2; return 1; }
  case "$v" in
    *$'\n'*|*$'\r'*) echo "[vault] 줄바꿈이 든 값은 저장할 수 없습니다: $k" >&2; return 1 ;;
  esac
  cur="$(_vault_plain)" || return 1
  { printf '%s\n' "$cur" | sed -e "/^${k}=/d" -e '/^$/d'; printf '%s=%s\n' "$k" "$v"; } | _vault_write
}

vault_get() { # KEY -> 값만 stdout (줄바꿈 없음). 없으면 1
  local k="$1" plain val
  vault_valid_key "$k" || { echo "[vault] 키 이름이 올바르지 않습니다: $k" >&2; return 1; }
  plain="$(_vault_plain)" || return 1
  val="$(printf '%s\n' "$plain" | sed -n "s/^${k}=//p" | head -n 1)"
  [ -n "$val" ] || return 1
  printf '%s' "$val"
}

vault_delete() { # KEY
  local k="$1" cur
  vault_valid_key "$k" || { echo "[vault] 키 이름이 올바르지 않습니다: $k" >&2; return 1; }
  cur="$(_vault_plain)" || return 1
  printf '%s\n' "$cur" | grep -q "^${k}=" || { echo "[vault] 없는 키입니다: $k" >&2; return 1; }
  printf '%s\n' "$cur" | sed -e "/^${k}=/d" -e '/^$/d' | _vault_write
}

vault_keys() { # 저장된 키 이름만(값은 출력하지 않음)
  local plain
  plain="$(_vault_plain)" || return 1
  printf '%s\n' "$plain" | sed -n 's/=.*//p'
}

# 암호문이 있고 DEK 로 풀리는가
vault_available() {
  [ -f "$VAULT_FILE" ] && [ -f "$DEK_FILE" ] && _vault_plain >/dev/null 2>&1
}

# 저장된 값을 NOTE_<이름> 환경변수로 올린다. 이미 지정된 환경변수는 유지한다.
# 결과: VAULT_LOADED(올린 개수), VAULT_LOADED_KEYS(올린 키 이름), VAULT_REJECTED_KEYS(검증에 걸려 올리지 않은 키 이름).
# VAULT_VALIDATOR 에 함수 이름을 주면 값을 인자로 불러서 0 이 아니면 올리지 않는다(예전에 규칙 없이 저장된 값이 init 을 막지 않게).
vault_export_presets() { # KEY...
  local k name v
  VAULT_LOADED=0; VAULT_LOADED_KEYS=""; VAULT_REJECTED_KEYS=""
  for k in "$@"; do
    name="NOTE_$k"
    [ -n "${!name:-}" ] && continue
    v="$(vault_get "$k" 2>/dev/null)" || continue
    if [ -n "${VAULT_VALIDATOR:-}" ] && ! "$VAULT_VALIDATOR" "$v" >/dev/null 2>&1; then
      VAULT_REJECTED_KEYS="${VAULT_REJECTED_KEYS:+$VAULT_REJECTED_KEYS }$k"
      continue
    fi
    export "$name=$v"
    VAULT_LOADED=$((VAULT_LOADED + 1))
    VAULT_LOADED_KEYS="${VAULT_LOADED_KEYS:+$VAULT_LOADED_KEYS }$k"
  done
  return 0
}

# 현재 환경의 값(예: MARIADB_PASSWORD)을 저장한다. 비어 있거나 자리표시 값은 건너뛴다. 저장한 키 이름을 VAULT_SAVED 에 둔다.
vault_save_secrets() { # KEY...
  local k v
  VAULT_SAVED=""
  for k in "$@"; do
    v="${!k:-}"
    case "$v" in ""|unused-existing-volume|placeholder-unused) continue ;; esac
    vault_set "$k" "$v" || return 1
    VAULT_SAVED="${VAULT_SAVED:+$VAULT_SAVED }$k"
  done
  return 0
}
