#!/usr/bin/env bash
# OS 별로 달라지는 부분(JDK 위치, docker 런타임)을 한곳에 모았다. 다른 스크립트가 source 해서 쓴다.
#   setup_java    JDK 25 를 찾아 JAVA_HOME / PATH 를 맞춘다. (.env 의 JAVA_HOME 이 있으면 그걸 우선)
#   setup_docker  docker 를 쓸 수 있게 맞춘다. 안 되면 OS 별 안내를 출력하고 종료한다.

JAVA_VERSION=25
OS="$(uname -s)"   # Darwin(macOS) | Linux

# <JAVA_HOME> 의 JDK 주요 버전(25, 17, 8 ...). 못 읽으면 빈 값.
java_major() {
  local v
  v="$("$1/bin/java" -XshowSettings:properties -version 2>&1 | sed -n 's/.*java.specification.version = //p' | head -1)" || true
  [[ "$v" == 1.* ]] && v="${v#1.}"
  echo "$v"
}

setup_java() {
  # 셸에 이미 잡혀 있는 JAVA_HOME(예: JDK 8)이 25 가 아니면 버리고 다시 찾는다.
  if [[ -n "${JAVA_HOME:-}" && "$(java_major "$JAVA_HOME")" != "$JAVA_VERSION" ]]; then
    JAVA_HOME=""
  fi
  if [[ -z "${JAVA_HOME:-}" ]]; then
    case "$OS" in
      Darwin)
        JAVA_HOME="$(/usr/libexec/java_home -v "$JAVA_VERSION" 2>/dev/null || true)" ;;
      Linux)
        local d
        # 폴더 이름은 믿지 않는다(jdk-17.0.25 는 25 가 아니고, default-java 는 25 일 수 있다). 실제 버전을 확인한다.
        for d in "$HOME"/.jdks/* /usr/lib/jvm/*; do
          [[ -x "$d/bin/java" && "$(java_major "$d")" == "$JAVA_VERSION" ]] && { JAVA_HOME="$d"; break; }
        done ;;
    esac
  fi
  if [[ -n "${JAVA_HOME:-}" ]]; then
    export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
  else
    unset JAVA_HOME
  fi
  [[ -n "${JAVA_HOME:-}" ]] || { echo "JDK $JAVA_VERSION 을 찾을 수 없습니다. 설치하거나 .env 의 JAVA_HOME 에 경로를 적으세요." >&2; exit 1; }
}

setup_docker() {
  case "$OS" in
    Darwin)
      command -v docker >/dev/null 2>&1 || { echo "docker 가 없습니다. 'brew install colima docker docker-compose' 후 'colima start' 를 실행하세요." >&2; exit 1; }
      docker info >/dev/null 2>&1 || { echo "docker 데몬에 연결할 수 없습니다. 'colima start' 를 실행하세요." >&2; exit 1; } ;;
    Linux)
      command -v docker >/dev/null 2>&1 || { echo "docker(또는 podman 의 docker 에뮬레이션)가 없습니다. 설치하세요." >&2; exit 1; }
      # podman 에뮬레이션: 기본 소켓(/var/run/docker.sock)은 root 전용이라 사용자용 소켓이 있으면 그걸 쓴다.
      local sock="/run/user/$(id -u)/podman/podman.sock"
      if [[ -z "${DOCKER_HOST:-}" && -S "$sock" && ! -w /var/run/docker.sock ]]; then
        export DOCKER_HOST="unix://$sock"
      fi ;;
    *) echo "지원하지 않는 OS 입니다: $OS" >&2; exit 1 ;;
  esac
}
