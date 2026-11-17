#!/usr/bin/env bash
# claude-toast 를 이 컴퓨터에 상시 설치한다.
#   1) toast-service 를 빌드하고, 로그인할 때 자동으로 켜지고 죽으면 다시 켜지는 서비스로 등록한다.
#      (macOS: launchd 사용자 에이전트, Linux: systemd --user)
#      macOS 의 백그라운드 서비스는 ~/Desktop 같은 보호 폴더를 읽을 수 없어서, 실행에 필요한 파일(jar, 스크립트)을
#      ~/.local/share/claude-toast 로 복사해 거기서 실행한다. 저장소 위치와 상관없이 늘 동작한다.
#   2) Claude Code 훅(Stop, Notification)을 ~/.claude/settings.json 에 등록한다.
#      사용자 전역 설정이라 어느 프로젝트에서 작업해도 알림이 온다. 기존 설정은 그대로 두고 우리 훅만 넣는다.
#
#   scripts/claude-toast/install.sh              설치 (이미 있으면 갱신. 코드를 바꾼 뒤에도 이걸 다시 실행)
#   scripts/claude-toast/install.sh --uninstall  서비스와 훅 제거
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$ROOT/scripts/lib/platform.sh"
LABEL=com.ethan.claude-toast
SETTINGS="$HOME/.claude/settings.json"
DEST="$HOME/.local/share/claude-toast"   # 설치된 파일들(서비스와 훅이 여기 것을 쓴다)
RUN="$DEST/run.sh"
NOTIFY="$DEST/notify.py"

deploy() {
  local jar="" f
  for f in "$ROOT"/backend/toast-service/build/libs/toast-service-*.jar; do
    [[ "$f" == *-plain.jar ]] || { jar="$f"; break; }
  done
  [[ -f "$jar" ]] || { echo "빌드된 jar 를 찾을 수 없습니다." >&2; exit 1; }
  mkdir -p "$DEST"
  # 실행 중인 jar 를 제자리에서 덮어쓰면 떠 있는 JVM 이 깨질 수 있어, 새 파일로 복사한 뒤 이름만 바꾼다.
  # (cp && mv 로 이으면 cp 가 실패해도 set -e 가 멈추지 않으므로 줄을 나눈다)
  cp "$jar" "$DEST/toast-service.jar.new"
  mv -f "$DEST/toast-service.jar.new" "$DEST/toast-service.jar"
  cp "$ROOT/scripts/claude-toast/run.sh" "$ROOT/scripts/claude-toast/notify.py" "$ROOT/scripts/lib/platform.sh" "$DEST/"
  chmod +x "$DEST/run.sh" "$DEST/notify.py"
}

edit_hooks() { # edit_hooks install|uninstall
  [[ "$1" == install ]] && mkdir -p "$(dirname "$SETTINGS")"
  python3 - "$SETTINGS" "$1" "$NOTIFY" <<'PY'
import json, os, shutil, sys
path, mode, notify = sys.argv[1:4]
path = os.path.realpath(path)  # settings.json 이 심볼릭 링크(dotfiles 관리)여도 링크를 지우지 않고 원본을 고친다
MARK = "claude-toast/notify.py"
if mode == "uninstall" and not os.path.exists(path):
    sys.exit(0)  # 지울 설정이 없으면 파일을 새로 만들지 않는다
cfg = {}
if os.path.exists(path):
    with open(path, encoding="utf-8") as f:
        cfg = json.load(f)  # 깨진 JSON 이면 여기서 멈추고 파일은 건드리지 않는다
    if not os.path.exists(path + ".bak-claude-toast"):
        shutil.copy2(path, path + ".bak-claude-toast")
hooks = cfg.setdefault("hooks", {})
for event, kind in (("Stop", "done"), ("Notification", "attention")):
    groups = hooks.get(event, [])
    for g in groups:  # 전에 등록한 우리 훅만 지우고 다른 훅은 그대로 둔다
        g["hooks"] = [h for h in g.get("hooks", []) if MARK not in h.get("command", "")]
    groups = [g for g in groups if g.get("hooks")]
    if mode == "install":
        groups.append({"hooks": [{"type": "command", "command": f'python3 "{notify}" {kind}', "timeout": 5}]})
    if groups:
        hooks[event] = groups
    else:
        hooks.pop(event, None)
if not hooks:
    cfg.pop("hooks", None)
tmp = path + ".tmp-claude-toast"  # 쓰다가 실패해도 settings.json 이 잘리지 않게 임시 파일에 쓴 뒤 바꿔치기한다
with open(tmp, "w", encoding="utf-8") as f:
    json.dump(cfg, f, ensure_ascii=False, indent=2)
    f.write("\n")
if os.path.exists(path):
    shutil.copymode(path, tmp)  # 원래 권한(예: 600)을 유지한다
os.replace(tmp, path)
PY
}

service_darwin() { # service_darwin install|uninstall
  local plist="$HOME/Library/LaunchAgents/$LABEL.plist" log="$HOME/Library/Logs/claude-toast.log"
  launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
  # bootout 은 바로 끝나지 않는다. 내려갈 때까지 기다리지 않고 bootstrap 하면 "Input/output error" 로 실패한다.
  for _ in $(seq 1 20); do
    launchctl print "gui/$(id -u)/$LABEL" >/dev/null 2>&1 || break
    sleep 0.5
  done
  [[ "$1" == install ]] || { rm -f "$plist"; return; }
  mkdir -p "$(dirname "$plist")" "$(dirname "$log")"
  cat > "$plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array><string>/bin/bash</string><string>$RUN</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$log</string>
  <key>StandardErrorPath</key><string>$log</string>
</dict></plist>
PLIST
  launchctl bootstrap "gui/$(id -u)" "$plist"
}

service_linux() { # service_linux install|uninstall
  local unit="$HOME/.config/systemd/user/claude-toast.service"
  if [[ "$1" != install ]]; then
    systemctl --user disable --now claude-toast.service 2>/dev/null || true
    rm -f "$unit"; systemctl --user daemon-reload; return
  fi
  mkdir -p "$(dirname "$unit")"
  cat > "$unit" <<UNIT
[Unit]
Description=claude-toast (Claude Code 알림을 브라우저 토스트로)

[Service]
ExecStart=/bin/bash $RUN
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
UNIT
  systemctl --user daemon-reload
  systemctl --user enable claude-toast.service
  systemctl --user restart claude-toast.service
}

service() {
  case "$OS" in
    Darwin) service_darwin "$1" ;;
    Linux)  service_linux "$1" ;;
    *) echo "지원하지 않는 OS 입니다: $OS" >&2; exit 1 ;;
  esac
}

if [[ "${1:-}" == "--uninstall" ]]; then
  service uninstall
  edit_hooks uninstall
  rm -rf "$DEST"
  echo "claude-toast 서비스와 Claude Code 훅을 제거했습니다."
  exit 0
fi

command -v python3 >/dev/null || { echo "python3 가 필요합니다." >&2; exit 1; }
setup_java
echo "toast-service 빌드 중..."
(cd "$ROOT/backend" && ./gradlew :toast-service:bootJar --console=plain -q)
deploy
service install
edit_hooks install

echo "서버 기동을 기다리는 중..."
for _ in $(seq 1 30); do
  curl --noproxy '*' -fsS -m 2 http://127.0.0.1:9208/api/toast/status >/dev/null 2>&1 && { ok=1; break; }
  sleep 1
done
[[ "${ok:-}" == 1 ]] || { echo "서버가 뜨지 않았습니다. 로그를 확인하세요($([[ $OS == Darwin ]] && echo ~/Library/Logs/claude-toast.log || echo 'journalctl --user -u claude-toast'))." >&2; exit 1; }
echo "설치했습니다. 서버: http://127.0.0.1:9208 · 훅: $SETTINGS"
echo "설정은 저장하면 보통 실행 중인 Claude Code 세션에도 바로 적용됩니다(안 되면 Claude Code 를 새로 시작하세요). 브라우저에서 Note(http://localhost:9201)를 열어 두면 토스트가 뜹니다."
