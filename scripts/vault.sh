#!/usr/bin/env bash
# 암호화해 보관한 비밀번호를 다루는 명령 (모듈: scripts/lib/vault.sh)
#
#   ./scripts/vault.sh set KEY      숨김 입력으로 저장 (파이프로 값을 줘도 된다: printf '%s' "$값" | ./scripts/vault.sh set KEY)
#   ./scripts/vault.sh get KEY      복호화한 값만 출력한다. 스크립트에서 환경변수로 넘길 때 쓰고, 화면·로그·대화에 남기지 말 것
#   ./scripts/vault.sh list         저장된 키 이름(값은 출력하지 않음)
#   ./scripts/vault.sh delete KEY   키 삭제
#   ./scripts/vault.sh path         암호문과 DEK 위치
#
# scripts/init-env.sh 가 입력받은 MARIADB_ROOT_PASSWORD, MARIADB_PASSWORD, REDIS_PASSWORD 를 여기에 보관한다.
# run-backend.sh 와 docker/manage.sh 는 실행할 때만 이 값을 복호화해 환경변수로 넘긴다.
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
. ./scripts/lib/vault.sh
# shellcheck disable=SC1091
. ./scripts/lib/secrets.sh # secret_invalid_reason (init-env.sh 와 같은 비밀번호 규칙)

usage() { sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-1}"; }

case "${1:-}" in
  set)
    k="${2:-}"; [ -n "$k" ] || usage
    if [ -t 0 ]; then
      IFS= read -r -s -p "${k} 값 (화면에 표시되지 않음): " v; echo >&2
      IFS= read -r -s -p "${k} 값 (확인): " v2; echo >&2
      [ "$v" = "$v2" ] || { echo "두 입력이 다릅니다." >&2; exit 1; }
    else
      IFS= read -r v || true # EOF 에서 개행 없이 끝나도 v 는 채워진다. 빈 입력이면 아래 검사/vault_set 이 이유를 알린다(set -e 로 조용히 죽지 않게)
    fi
    # init-env.sh 가 쓰는 키는 같은 규칙으로 검사한다(규칙에 어긋난 값이 보관돼 나중에 DB/Redis 가 못 뜨는 걸 막는다).
    case "$k" in
      MARIADB_ROOT_PASSWORD|MARIADB_PASSWORD|REDIS_PASSWORD)
        reason="$(secret_invalid_reason "$v" 72)" || { echo "[vault] $k: $reason" >&2; exit 1; } ;;
    esac
    vault_set "$k" "$v"
    echo "저장했습니다: $k  (암호문: $VAULT_FILE)" >&2 ;;
  get)
    [ -n "${2:-}" ] || usage
    vault_get "$2" || { echo "[vault] 값을 읽지 못했습니다: $2" >&2; exit 1; }
    if [ -t 1 ]; then echo; fi ;; # 터미널이면 줄바꿈을 붙인다(명령 치환에서는 붙이지 않는다). && 로 쓰면 파이프일 때 종료 코드가 1 이 된다
  list)   vault_keys ;;
  delete) [ -n "${2:-}" ] || usage; vault_delete "$2"; echo "삭제했습니다: $2" >&2 ;;
  path)   echo "암호문: $VAULT_FILE"; echo "DEK   : $DEK_FILE" ;;
  -h|--help|help) usage 0 ;;
  *) usage ;;
esac
