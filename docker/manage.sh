#!/usr/bin/env bash
# 인프라 컨테이너(mariadb, 나중에 mqtt/kafka 등)를 쉽게 켜고 끄고 지우는 스크립트.
#
# 사용법: ./manage.sh <명령> <서비스>
#   up       <서비스>   설치(이미지 받기)하고 실행
#   down     <서비스>   중지 (데이터는 유지)
#   destroy  <서비스>   컨테이너 + 데이터(볼륨) 완전 삭제
#   logs     <서비스>   로그 보기
#   ps       [서비스]   상태 보기 (서비스 생략 시 전체)
#   list                사용 가능한 서비스 목록
#
# 서비스 추가: docker/<이름>/docker-compose.yml 만 만들면 바로 사용할 수 있다.

set -euo pipefail
cd "$(dirname "$0")"
# 비밀번호 같은 값은 프로젝트 루트의 .env 에 둔다(커밋되지 않음). 없으면 scripts/init-env.sh 로 만든다.
ENV_FILE="$(cd .. && pwd)/.env"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "루트에 .env 가 없습니다. 먼저 scripts/init-env.sh 를 실행하고 비밀번호를 채우세요." >&2
  exit 1
fi
# compose 가 비밀번호 없이 뜨지 않도록 서비스별로 필요한 값을 미리 확인한다.
required_env() { # required_env <서비스>
  case "$1" in
    mariadb) echo "MARIADB_ROOT_PASSWORD MARIADB_PASSWORD" ;;
    redis)   echo "REDIS_PORT REDIS_PASSWORD" ;;
    *)       echo "" ;;
  esac
}
check_env() { # check_env <서비스>
  local v
  for v in $(required_env "$1"); do
    if ! grep -Eq "^${v}=.+" "$ENV_FILE"; then
      echo ".env 의 $v 값이 비어 있습니다. 채운 뒤 다시 실행하세요." >&2
      exit 1
    fi
  done
}

source "$(cd .. && pwd)/scripts/lib/platform.sh"
setup_docker   # OS 별로 docker 를 쓸 수 있게 맞춘다 (macOS: colima, Linux: podman 소켓)

if command -v docker-compose >/dev/null 2>&1; then
  COMPOSE=(docker-compose)
else
  COMPOSE=(docker compose)
fi

services() { for f in */docker-compose.yml; do dirname "$f"; done; }

usage() { sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-1}"; }

compose() { # compose <서비스> <인자...>
  local svc="$1"; shift
  # up 이외(down/logs/ps 등)는 비밀번호가 필요 없다. compose 의 필수값 검사에 걸리지 않게 빈 값만 임시로 채운다.
  if [[ "${1:-}" != "up" ]]; then
    local v
    for v in $(required_env "$svc"); do
      grep -Eq "^${v}=.+" "$ENV_FILE" || { [[ "$v" == *PORT ]] && export "$v=0" || export "$v=unused"; }
    done
  fi
  [[ -f "$svc/docker-compose.yml" ]] || { echo "알 수 없는 서비스: $svc (사용 가능: $(services | xargs))" >&2; exit 1; }
  (cd "$svc" && "${COMPOSE[@]}" --env-file "$ENV_FILE" "$@")
}

cmd="${1:-}"; svc="${2:-}"
case "$cmd" in
  up)      [[ -n "$svc" ]] || usage; check_env "$svc"; compose "$svc" up -d ;;
  down)    [[ -n "$svc" ]] || usage; compose "$svc" down ;;
  destroy)
    [[ -n "$svc" ]] || usage
    read -r -p "'$svc' 컨테이너와 데이터 볼륨을 모두 삭제합니다. 계속할까요? [y/N] " ans
    [[ "$ans" == "y" || "$ans" == "Y" ]] || { echo "취소했습니다."; exit 0; }
    compose "$svc" down -v --remove-orphans ;;
  logs)    [[ -n "$svc" ]] || usage; compose "$svc" logs -f --tail=100 ;;
  ps)      if [[ -n "$svc" ]]; then compose "$svc" ps; else for s in $(services); do compose "$s" ps; done; fi ;;
  list)    services ;;
  -h|--help|help) usage 0 ;;
  *)       usage ;;
esac
