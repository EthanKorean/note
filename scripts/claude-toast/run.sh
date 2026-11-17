#!/usr/bin/env bash
# claude-toast 서버(toast-service, 9208)를 실행한다. .env 가 필요 없다.
#  - 설치된 위치(~/.local/share/claude-toast): install.sh 가 복사해 둔 jar 를 쓴다. 서비스 관리자(launchd/systemd)가 로그인 때 호출한다.
#  - 저장소에서 직접 실행: backend/toast-service/build/libs 의 jar 를 쓴다.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
if [[ -f "$DIR/toast-service.jar" ]]; then
  LIB="$DIR/platform.sh"; JAR="$DIR/toast-service.jar"
else
  ROOT="$(cd "$DIR/../.." && pwd)"
  LIB="$ROOT/scripts/lib/platform.sh"; JAR=""
  for f in "$ROOT"/backend/toast-service/build/libs/toast-service-*.jar; do
    [[ "$f" == *-plain.jar ]] || { JAR="$f"; break; }
  done
fi
[[ -f "$JAR" ]] || { echo "toast-service jar 가 없습니다. scripts/claude-toast/install.sh 를 먼저 실행하세요." >&2; exit 1; }
source "$LIB"
setup_java   # OS 별로 JDK 25 를 찾는다
exec java -jar "$JAR"
