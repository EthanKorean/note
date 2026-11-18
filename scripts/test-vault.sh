#!/usr/bin/env bash
# scripts/lib/vault.sh 와 scripts/vault.sh 시험. 임시 폴더에서만 동작하며 실제 .secrets.enc/DEK 는 건드리지 않는다.
#   ./scripts/test-vault.sh            기본 bash 로 실행
#   /bin/bash ./scripts/test-vault.sh  macOS 기본 bash 3.2 로 실행(호환 확인)
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
# 시험 묶음을 서브셸로 돌리므로 카운터는 파일에 기록한다.
: > "$T/pass"; : > "$T/fail"
ok()   { echo . >> "$T/pass"; printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad()  { echo . >> "$T/fail"; printf '  \033[31m✗\033[0m %s\n' "$1"; }
check() { # 설명 명령... : 명령이 0 이면 통과
  local d="$1"; shift
  if "$@" >/dev/null 2>&1; then ok "$d"; else bad "$d"; fi
}
checkfail() { # 설명 명령... : 명령이 0 이 아니면 통과
  local d="$1"; shift
  if "$@" >/dev/null 2>&1; then bad "$d"; else ok "$d"; fi
}
perm() { stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1"; } # GNU 의 stat -f 는 파일시스템 정보라 -c 를 먼저 시도한다

# 각 시험은 서브셸 + 새 임시 위치에서 돌린다.
new_env() { local d; d="$(mktemp -d "$T/env.XXXXXX")"; export NOTE_VAULT_FILE="$d/.secrets.enc" NOTE_DEK_FILE="$d/cfg/note/dek.key"; }
V() { "$ROOT/scripts/vault.sh" "$@"; }

echo "bash $BASH_VERSION"

echo "[기본 동작]"
( new_env
  checkfail "암호문이 없으면 get 이 실패한다" V get MARIADB_PASSWORD
  check     "get 이 실패해도 파일을 만들지 않는다" test ! -e "$NOTE_VAULT_FILE" -a ! -e "$NOTE_DEK_FILE"
  printf '%s' 'Ab1!@#%^&*()_+-=[]{};:,.<>/?~|' | V set MARIADB_PASSWORD 2>/dev/null
  check "set 후 DEK 가 생긴다" test -s "$NOTE_DEK_FILE"
  check "set 후 암호문이 생긴다" test -s "$NOTE_VAULT_FILE"
  [ "$(perm "$NOTE_DEK_FILE")" = 600 ] && ok "DEK 권한 600" || bad "DEK 권한 600 (실제 $(perm "$NOTE_DEK_FILE"))"
  [ "$(perm "$NOTE_VAULT_FILE")" = 600 ] && ok "암호문 권한 600" || bad "암호문 권한 600 (실제 $(perm "$NOTE_VAULT_FILE"))"
  [ "$(perm "$(dirname "$NOTE_DEK_FILE")")" = 700 ] && ok "새로 만든 DEK 폴더 권한 700" || bad "DEK 폴더 권한 700"
  check "get 이 파이프/명령 치환에서도 종료 코드 0 으로 끝난다" bash -c "'$ROOT/scripts/vault.sh' get MARIADB_PASSWORD | cat >/dev/null; [ \"\${PIPESTATUS[0]}\" = 0 ] && v=\$('$ROOT/scripts/vault.sh' get MARIADB_PASSWORD)"
  [ "$(V get MARIADB_PASSWORD)" = 'Ab1!@#%^&*()_+-=[]{};:,.<>/?~|' ] && ok "특수문자 값을 그대로 복호화한다" || bad "특수문자 값 왕복"
) 2>&1

echo "[암호문에 평문이 남지 않는다]"
( new_env
  printf '%s' 'SuperSecretPw123' | V set REDIS_PASSWORD 2>/dev/null
  checkfail "암호문에 비밀번호가 보이지 않는다" grep -a -q 'SuperSecretPw123' "$NOTE_VAULT_FILE"
  checkfail "암호문에 키 이름이 보이지 않는다" grep -a -q 'REDIS_PASSWORD' "$NOTE_VAULT_FILE"
  checkfail "암호문에 매직 문자열이 보이지 않는다" grep -a -q 'HRVAULT1' "$NOTE_VAULT_FILE"
  cp "$NOTE_VAULT_FILE" "$T/first.enc"
  printf '%s' 'SuperSecretPw123' | V set REDIS_PASSWORD 2>/dev/null
  checkfail "같은 내용을 다시 저장해도 암호문이 다르다(salt)" cmp -s "$NOTE_VAULT_FILE" "$T/first.enc"
) 2>&1

echo "[여러 키, 덮어쓰기, 삭제]"
( new_env
  printf '%s' 'rootpw-1111' | V set MARIADB_ROOT_PASSWORD 2>/dev/null
  printf '%s' 'dbpw-2222'   | V set MARIADB_PASSWORD 2>/dev/null
  printf '%s' 'adminpw-3333'| V set REDIS_PASSWORD 2>/dev/null
  printf '%s' 'dbpw-4444'   | V set MARIADB_PASSWORD 2>/dev/null
  [ "$(V get MARIADB_PASSWORD)" = 'dbpw-4444' ] && ok "같은 키를 덮어쓴다" || bad "덮어쓰기"
  [ "$(V get MARIADB_ROOT_PASSWORD)" = 'rootpw-1111' ] && [ "$(V get REDIS_PASSWORD)" = 'adminpw-3333' ] && ok "다른 키는 그대로다" || bad "다른 키 유지"
  [ "$(V list | sort | tr '\n' ' ')" = 'MARIADB_PASSWORD MARIADB_ROOT_PASSWORD REDIS_PASSWORD ' ] && ok "list 는 키 이름만 중복 없이 보여 준다" || bad "list 결과 ($(V list | tr '\n' ' '))"
  checkfail "list 출력에 값이 없다" bash -c "V() { '$ROOT/scripts/vault.sh' \"\$@\"; }; V list | grep -q -e pw-"
  V delete MARIADB_PASSWORD 2>/dev/null
  checkfail "삭제한 키는 get 이 실패한다" V get MARIADB_PASSWORD
  [ "$(V get REDIS_PASSWORD)" = 'adminpw-3333' ] && ok "삭제해도 다른 키는 남는다" || bad "삭제 후 유지"
  checkfail "없는 키 삭제는 실패한다" V delete NOPE_KEY
) 2>&1

echo "[잘못된 키·손상·분실]"
( new_env
  printf '%s' 'secretvalue1' | V set MARIADB_PASSWORD 2>/dev/null
  cp "$NOTE_DEK_FILE" "$T/dek.good"
  printf 'totally-wrong-key\n' > "$NOTE_DEK_FILE"
  checkfail "틀린 DEK 로는 복호화되지 않는다" V get MARIADB_PASSWORD
  msg="$(V get MARIADB_PASSWORD 2>&1 || true)"
  case "$msg" in *복호화*) ok "틀린 DEK 에서 이유를 알려 준다" ;; *) bad "틀린 DEK 메시지: $msg" ;; esac
  cp "$T/dek.good" "$NOTE_DEK_FILE"
  [ "$(V get MARIADB_PASSWORD)" = secretvalue1 ] && ok "원래 DEK 로 돌아가면 다시 풀린다" || bad "원래 DEK 복구"
  # 손상: 암호문 가운데 바이트를 바꾼다
  cp "$NOTE_VAULT_FILE" "$T/vault.good"
  python3 - "$NOTE_VAULT_FILE" <<'PY'
import sys
p=sys.argv[1]; b=bytearray(open(p,'rb').read()); b[len(b)//2]^=0xFF; open(p,'wb').write(b)
PY
  checkfail "손상된 암호문은 복호화되지 않는다" V get MARIADB_PASSWORD
  cp "$T/vault.good" "$NOTE_VAULT_FILE"
  # DEK 분실: 암호문은 있는데 DEK 가 없다
  rm -f "$NOTE_DEK_FILE"
  checkfail "DEK 가 없으면 get 이 실패한다" V get MARIADB_PASSWORD
  printf '%s' 'x-new-value1' | V set OTHER_KEY 2>/dev/null && bad "DEK 가 없을 때 set 이 새 DEK 를 만들어 덮어썼다" || ok "DEK 가 없으면 set 도 멈춘다(새 DEK 로 덮어쓰지 않는다)"
  check "그 사이 암호문은 그대로다" cmp -s "$NOTE_VAULT_FILE" "$T/vault.good"
  check "DEK 를 몰래 새로 만들지 않는다" test ! -e "$NOTE_DEK_FILE"
) 2>&1

echo "[입력 검증]"
( new_env
  checkfail "소문자 키 이름 거부" bash -c "printf x1y2z3w4 | '$ROOT/scripts/vault.sh' set lower_key"
  for loc in C en_US.UTF-8 ko_KR.UTF-8; do
    checkfail "소문자 키 이름 거부 (LC_ALL=$loc)" env LC_ALL=$loc bash -c "printf x1y2z3w4 | '$ROOT/scripts/vault.sh' set abc_key"
  done
  checkfail "숫자로 시작하는 키 이름 거부" bash -c "printf x1y2z3w4 | '$ROOT/scripts/vault.sh' set 1KEY"
  checkfail "KEY=VALUE 형태 이름 거부" bash -c "printf x1y2z3w4 | '$ROOT/scripts/vault.sh' set 'A=B'"
  checkfail "빈 값 거부" bash -c "printf '' | '$ROOT/scripts/vault.sh' set EMPTY_KEY"
  checkfail "줄바꿈이 든 값 거부(두 번째 줄로 키를 심는 주입 방지)" bash -c "source '$ROOT/scripts/lib/vault.sh'; vault_set EVIL_KEY \$'a\nINJECTED=1'"
  checkfail "거부된 입력으로 암호문이 만들어지지 않는다" test -e "$NOTE_VAULT_FILE"
) 2>&1

echo "[init 과 같은 비밀번호 규칙]"
( new_env
  checkfail "공백이 든 REDIS_PASSWORD 거부" bash -c "printf 'has space 1234' | '$ROOT/scripts/vault.sh' set REDIS_PASSWORD"
  checkfail "8자 미만 MARIADB_PASSWORD 거부" bash -c "printf 'short1' | '$ROOT/scripts/vault.sh' set MARIADB_PASSWORD"
  checkfail "한글이 든 MARIADB_ROOT_PASSWORD 거부" bash -c "printf '비밀번호12345' | '$ROOT/scripts/vault.sh' set MARIADB_ROOT_PASSWORD"
  checkfail "따옴표가 든 REDIS_PASSWORD 거부" bash -c "printf 'abc\"defg1234' | '$ROOT/scripts/vault.sh' set REDIS_PASSWORD"
  checkfail "거부된 값으로 암호문이 만들어지지 않는다" test -e "$NOTE_VAULT_FILE"
  check     "규칙에 맞는 값은 저장된다" bash -c "printf 'valid-pass-1234' | '$ROOT/scripts/vault.sh' set REDIS_PASSWORD"
  check     "init 규칙이 없는 다른 키는 짧아도 저장된다" bash -c "printf 'abc' | '$ROOT/scripts/vault.sh' set SOME_TOKEN"
) 2>&1

echo "[init.sh 연동 함수]"
( new_env
  source "$ROOT/scripts/lib/vault.sh"
  export MARIADB_ROOT_PASSWORD=unused-existing-volume MARIADB_PASSWORD=dbpass-9999 REDIS_PASSWORD=""
  vault_save_secrets MARIADB_ROOT_PASSWORD MARIADB_PASSWORD REDIS_PASSWORD
  [ "$VAULT_SAVED" = MARIADB_PASSWORD ] && ok "자리표시 값·빈 값은 저장하지 않는다" || bad "저장된 키: '$VAULT_SAVED'"
  unset NOTE_MARIADB_PASSWORD NOTE_REDIS_PASSWORD
  export NOTE_REDIS_PASSWORD=already-set-by-user
  vault_save_secrets MARIADB_PASSWORD >/dev/null
  printf '%s' 'adminfromvault' | V set REDIS_PASSWORD 2>/dev/null
  vault_export_presets MARIADB_PASSWORD REDIS_PASSWORD
  [ "${NOTE_MARIADB_PASSWORD:-}" = dbpass-9999 ] && ok "저장된 값을 NOTE_ 환경변수로 올린다" || bad "NOTE_MARIADB_PASSWORD 올리기"
  [ "$NOTE_REDIS_PASSWORD" = already-set-by-user ] && ok "이미 지정한 환경변수는 덮어쓰지 않는다" || bad "환경변수 우선순위"
  vault_available && ok "vault_available: 풀리면 참" || bad "vault_available"
  [ "$VAULT_LOADED_KEYS" = MARIADB_PASSWORD ] && ok "올린 키 이름을 VAULT_LOADED_KEYS 에 둔다(이미 환경에 있는 REDIS_PASSWORD 는 제외)" || bad "VAULT_LOADED_KEYS: '$VAULT_LOADED_KEYS'"
  # 검증 함수: 규칙에 안 맞게 저장돼 있던 값(예전 버전/직접 저장)은 올리지 않고 이름만 알린다
  source "$ROOT/scripts/lib/secrets.sh"
  vault_set REDIS_PASSWORD 'has space 1234' >/dev/null 2>&1
  vault_set MARIADB_ROOT_PASSWORD rootpass-1234 >/dev/null 2>&1
  unset NOTE_REDIS_PASSWORD NOTE_MARIADB_ROOT_PASSWORD
  vok() { secret_invalid_reason "$1" 72; }
  VAULT_VALIDATOR=vok vault_export_presets MARIADB_ROOT_PASSWORD REDIS_PASSWORD
  [ "$VAULT_REJECTED_KEYS" = REDIS_PASSWORD ] && [ "$VAULT_LOADED_KEYS" = MARIADB_ROOT_PASSWORD ] && [ -z "${NOTE_REDIS_PASSWORD:-}" ] && [ "$NOTE_MARIADB_ROOT_PASSWORD" = rootpass-1234 ] \
    && ok "규칙에 안 맞는 보관 값은 올리지 않고 VAULT_REJECTED_KEYS 로 알린다" || bad "검증 함수 (거부 '$VAULT_REJECTED_KEYS', 올림 '$VAULT_LOADED_KEYS')"
  printf 'wrong\n' > "$NOTE_DEK_FILE"
  vault_available && bad "틀린 DEK 에서 vault_available 이 참" || ok "틀린 DEK 면 vault_available 이 거짓"
) 2>&1

pass="$(wc -l < "$T/pass" | tr -d ' ')"; fail="$(wc -l < "$T/fail" | tr -d ' ')"
echo
echo "결과: 통과 $pass / 실패 $fail"
[ "$fail" -eq 0 ]
