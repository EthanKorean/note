#!/usr/bin/env bash
# 백엔드를 .env 값으로 실행한다.
#   scripts/run-backend.sh juso      juso-service (9204)
#   scripts/run-backend.sh gateway   gateway (9203)
#   scripts/run-backend.sh catch     catch-service (9206, 캐치마인드)
#   scripts/run-backend.sh image     image-service (9205, Redis 필요: docker/manage.sh up redis)
# 터미널을 하나씩 열어 각각 실행한다. DB 는 먼저 docker/manage.sh up mariadb 로 켠다.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[[ -f "$ROOT/.env" ]] || { echo ".env 가 없습니다. scripts/init-env.sh 를 먼저 실행하세요." >&2; exit 1; }

set -a; source "$ROOT/.env"; set +a
# .env 에서 비워 둔 값은 기본값으로 대체한다.
[[ -n "${JAVA_HOME:-}" ]] && export PATH="$JAVA_HOME/bin:$PATH" || unset JAVA_HOME
export JUSO_DATA_FILE="${JUSO_DATA_FILE:-$ROOT/generate-data/result/202610_juso.txt}"
command -v java >/dev/null || { echo "java 를 찾을 수 없습니다. .env 의 JAVA_HOME 에 JDK 17 경로를 적으세요." >&2; exit 1; }

case "${1:-}" in
  juso)    [[ -n "${JUSO_DB_PASSWORD:-}" ]] || { echo ".env 의 JUSO_DB_PASSWORD 가 비어 있습니다." >&2; exit 1; }
           cd "$ROOT/backend" && exec ./gradlew :juso-service:bootRun ;;
  gateway) cd "$ROOT/backend" && exec ./gradlew :gateway:bootRun ;;
  image)   export IMAGE_DIR="${IMAGE_DIR:-$ROOT/image-data}"
           for v in REDIS_PASSWORD JUSO_DB_URL JUSO_DB_USER JUSO_DB_PASSWORD; do
             [[ -n "${!v:-}" ]] || { echo ".env 의 $v 가 비어 있습니다." >&2; exit 1; }
           done
           cd "$ROOT/backend" && exec ./gradlew :image-service:bootRun ;;
  catch)   cd "$ROOT/backend" && exec ./gradlew :catch-service:bootRun ;;
  *)       sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
