#!/usr/bin/env bash
# 비밀번호 규칙과 보관함 연동. scripts/init-env.sh, scripts/run-backend.sh, docker/manage.sh 가 source 한다.
#
# 원칙: 비밀번호는 평문으로 어떤 파일에도 저장하지 않는다(.env 에는 포트/DB 이름 같은 비밀 아닌 설정만 둔다).
# init-env.sh 가 입력받은 값은 scripts/lib/vault.sh 가 DEK 로 암호화해 .secrets.enc 에 보관하고,
# 실행 스크립트가 필요할 때만 복호화해 프로세스 환경변수로 넘긴다.
# macOS 기본 bash 3.2 호환.

# shellcheck disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/vault.sh"

SECRET_MIN=8
# 보관하는 비밀번호. JUSO_DB_PASSWORD 는 MariaDB root 비밀번호와 같은 값이라 따로 보관하지 않고 load_secrets_env 가 채운다.
SECRET_KEYS="MARIADB_ROOT_PASSWORD MARIADB_PASSWORD REDIS_PASSWORD"
# .env 에서 "KEY=값" 줄의 앞부분. bash source 와 docker compose 는 앞 공백과 "export " 접두어도 같은 줄로 읽는다(grep -E 용).
ENV_LINE_PRE='^[[:space:]]*(export[[:space:]]+)?'

# 파일(.env)에서 그 키의 실제 값. bash source 와 docker compose 가 읽는 값과 같게 마지막 줄을 쓰고, CR·줄 끝 공백·인라인 주석(공백 뒤 #)을 없애고
# 짝이 맞는 따옴표 한 쌍을 벗긴다. 줄이 없거나 값이 비어 있으면(KEY=, KEY="", KEY= # 메모) 아무것도 출력하지 않는다. 항상 0 을 돌려준다.
env_file_value() { # <파일> <키>
  local v re_dq re_sq
  re_dq='^"([^"]*)"[[:space:]]*(#.*)?$'; re_sq="^'([^']*)'[[:space:]]*(#.*)?\$"
  # 바이트 단위(LC_ALL=C, grep -a)로 읽는다. NUL 이 든 파일에서 grep 이 "Binary file ... matches" 를 값으로 내보내거나, 잘못된 UTF-8 에서 sed 가 조용히 빈 값을 내는 걸 막는다.
  v="$(LC_ALL=C grep -aE "${ENV_LINE_PRE}${2}=" "$1" 2>/dev/null | tail -n 1 | LC_ALL=C sed -E "s/${ENV_LINE_PRE}${2}=//" | LC_ALL=C tr -d '\r' || true)"
  if [[ "$v" =~ $re_dq ]]; then v="${BASH_REMATCH[1]}"
  elif [[ "$v" =~ $re_sq ]]; then v="${BASH_REMATCH[1]}"
  else v="$(printf '%s' "$v" | LC_ALL=C sed -E -e 's/[[:space:]]+#.*$//' -e 's/[[:space:]]+$//')"
  fi
  printf '%s' "$v"
}

# 길이/문자 검증. docker compose 와 쉘에 그대로 전개되므로 ' " \ $ ` 공백은 허용하지 않는다.
# 실패 시 사유를 stdout 으로 출력하고 1 반환.
secret_invalid_reason() { # value maxbytes
  local v="$1" max="$2" bytes
  bytes="$(printf '%s' "$v" | wc -c | tr -d ' ')"
  if [ "$bytes" -lt "$SECRET_MIN" ]; then echo "${SECRET_MIN}자 이상이어야 합니다."; return 1; fi
  if [ "$bytes" -gt "$max" ]; then echo "최대 ${max}바이트까지 가능합니다."; return 1; fi
  # DB 초기화 SQL/쉘 인코딩 문제를 피하기 위해 출력 가능한 ASCII 만 허용
  if printf '%s' "$v" | LC_ALL=C grep -q '[^ -~]'; then echo "영문/숫자/기호(ASCII)만 사용할 수 있습니다."; return 1; fi
  case "$v" in
    *[\'\"\\\$\`\ ]*) echo "작은따옴표 ' 큰따옴표 \" 역슬래시 \\ 달러 \$ 백틱 \` 공백은 사용할 수 없습니다."; return 1 ;;
  esac
  return 0
}


# 보관함의 비밀번호를 환경변수로 올린다. 이미 환경에 있는 값(직접 지정한 값)은 유지한다.
# 보관함이 없으면 조용히, 있는데 못 풀면(DEK 없음/다름/손상) 이유를 stderr 로 알리고 0 을 돌려준다(필요한 값이 없다는 오류는 쓰는 쪽이 낸다).
load_secrets_env() {
  local k v
  # _vault_plain 이 못 푸는 이유를 직접 출력한다. 한 번만 풀어 확인한다(vault_available 을 쓰면 오류 이유가 가려진다).
  if [ -f "$VAULT_FILE" ] && _vault_plain >/dev/null; then
    for k in $SECRET_KEYS; do
      if [[ -n "${!k:-}" ]]; then continue; fi
      v="$(vault_get "$k" 2>/dev/null)" || continue
      export "$k=$v"
    done
  fi
  # juso-service 는 FILE 권한이 필요해 root 로 접속한다. 비밀번호는 root 비밀번호와 같다.
  if [[ -z "${JUSO_DB_PASSWORD:-}" && -n "${MARIADB_ROOT_PASSWORD:-}" ]]; then
    export JUSO_DB_PASSWORD="$MARIADB_ROOT_PASSWORD"
  fi
  return 0
}

# .env 에 예전 방식의 평문 비밀번호가 남아 있으면 알린다(값은 출력하지 않는다).
warn_plaintext_env() { # <.env 경로>
  local f="$1" k found=""
  [[ -f "$f" ]] || return 0
  for k in $SECRET_KEYS JUSO_DB_PASSWORD; do
    if [[ -n "$(env_file_value "$f" "$k")" ]]; then found="$found $k"; fi
  done
  if [[ -n "$found" ]]; then
    echo "[경고] .env 에 평문 비밀번호가 남아 있습니다:${found}. scripts/init-env.sh 를 실행하면 보관함의 값과 같은 것은 .env 에서 지웁니다. 규칙에 안 맞거나 보관함과 다른 값은 직접 정리하세요(.env 의 값이 우선 적용됩니다)." >&2
  fi
  return 0
}
