#!/usr/bin/env python3
"""Claude Code 훅 → claude-toast 서버(POST /api/toast).

  notify.py done        Stop 훅: 응답이 끝났을 때 "작업이 끝났어요"
  notify.py attention   Notification 훅: 권한이나 입력이 필요할 때

훅은 stdin 으로 JSON(cwd, transcript_path, message 등)을 받는다. 서버가 꺼져 있어도 Claude Code 를 방해하면 안 되므로
어떤 오류가 나도 조용히 끝낸다(출력 없음, 종료 코드 0, 최대 2초). 외부 패키지 없이 표준 라이브러리만 쓴다.

환경변수: CLAUDE_TOAST_URL(서버 주소), CLAUDE_TOAST_MIN_SECONDS(이보다 짧게 끝난 응답은 알리지 않는다. 기본 15, 0 이면 항상 알림),
          CLAUDE_TOAST_IDLE(1 이면 "입력을 기다리는 중" 알림도 보낸다. 기본은 건너뜀)
"""
import json
import os
import sys
from datetime import datetime, timezone
from urllib import request
from urllib.parse import urlparse

URL = os.environ.get("CLAUDE_TOAST_URL", "http://127.0.0.1:9208/api/toast")


def seconds(name, default):
    try:
        return float(os.environ.get(name, default))
    except ValueError:
        return default  # 잘못된 값이어도 훅은 조용히 동작해야 한다


MIN_SECONDS = seconds("CLAUDE_TOAST_MIN_SECONDS", 15.0)
TAIL_BYTES = 512 * 1024  # 대화 기록(JSONL)은 수 MB 까지 커지므로 끝부분만 읽는다


def tail_entries(path):
    with open(path, "rb") as f:
        size = f.seek(0, os.SEEK_END)
        f.seek(max(0, size - TAIL_BYTES))
        lines = f.read().split(b"\n")
    if size > TAIL_BYTES:
        lines = lines[1:]  # 잘린 첫 줄은 버린다
    for line in lines:
        try:
            entry = json.loads(line)
        except ValueError:
            continue
        if isinstance(entry, dict):  # null, [], 숫자처럼 객체가 아닌 줄은 건너뛴다
            yield entry


def is_prompt(content):
    """사용자가 직접 입력한 턴인가. 문자열이거나, 이미지를 붙인 입력처럼 글·이미지 블록으로 된 목록. 도구 결과(tool_result)는 아니다."""
    if isinstance(content, str):
        return True
    blocks = [b for b in content if isinstance(b, dict)] if isinstance(content, list) else []
    return not any(b.get("type") == "tool_result" for b in blocks) and any(b.get("type") in ("text", "image") for b in blocks)


def last_turn(path):
    """(마지막 사용자 입력 시각, 그 뒤 마지막 어시스턴트 글). 끝부분에 사용자 입력이 없으면 시각은 None."""
    started, text = None, ""
    for e in tail_entries(path):
        if e.get("isSidechain"):
            continue  # 서브에이전트의 대화는 제외
        msg = e.get("message") if isinstance(e.get("message"), dict) else {}
        content = msg.get("content")
        if e.get("type") == "user" and is_prompt(content) and not e.get("isMeta"):
            try:
                started = datetime.fromisoformat(str(e["timestamp"]).replace("Z", "+00:00"))
            except (KeyError, ValueError):
                continue  # 시각을 못 읽는 줄 하나 때문에 요약과 짧은 대화 걸러내기를 모두 잃지 않는다
            text = ""  # 새 턴이 시작되면 이전 턴의 글은 버린다
        elif e.get("type") == "assistant" and isinstance(content, list):
            for block in content:
                if isinstance(block, dict) and block.get("type") == "text" and block.get("text", "").strip():
                    text = block["text"]
    return started, text


def summary(text):
    for line in text.splitlines():
        line = line.strip().lstrip("#>*- ").strip()
        if line:
            return line[:140]
    return ""


def main():
    kind = sys.argv[1] if len(sys.argv) > 1 else "info"
    payload = {} if sys.stdin.isatty() else json.load(sys.stdin)
    body = {"kind": kind, "project": os.path.basename(payload.get("cwd") or os.getcwd())}
    if kind == "done":
        message = ""
        try:
            started, text = last_turn(payload["transcript_path"])
            if started and (datetime.now(timezone.utc) - started).total_seconds() < MIN_SECONDS:
                return  # 금방 끝난 대화는 알리지 않는다
            message = summary(text)
        except Exception:  # 키 없음, 파일 오류, 시각에 시간대가 없음(TypeError), 줄이 객체가 아님(AttributeError) 등
            message = ""  # 기록을 못 읽어도 "끝났다"는 알림은 보낸다
        body["message"] = message
    else:
        message = str(payload.get("message", ""))
        # 입력 없이 60초가 지나면 오는 "입력을 기다리는 중" 알림은 자주 와서 건너뛴다. 작업이 끝났다는 알림은 Stop 훅이 따로 보낸다.
        idle = payload.get("notification_type") == "idle_prompt" or "waiting for your input" in message.lower()
        if idle and os.environ.get("CLAUDE_TOAST_IDLE", "").strip().lower() in ("", "0", "false", "no", "off"):
            return
        body["message"] = message
    req = request.Request(URL, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"}, method="POST")
    # 이 컴퓨터의 서버로 보내는 것이라 프록시를 거치지 않는다(http_proxy 가 설정된 환경에서 127.0.0.1 로 가는 요청이 프록시로 새어 조용히 실패한다).
    host = urlparse(URL).hostname or ""
    opener = request.build_opener(request.ProxyHandler({})) if host in ("127.0.0.1", "localhost", "::1") else request.build_opener()
    opener.open(req, timeout=2).close()


if __name__ == "__main__":
    try:
        main()
    except Exception:
        pass
