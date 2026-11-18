#!/usr/bin/env bash
# scripts/init-env.sh 와 scripts/lib/secrets.sh(load_secrets_env, warn_plaintext_env) 시험.
# 임시 폴더에 scripts/ 와 .env.example 만 복사해 가짜 평문 .env 로 시험하며, 실제 .env/.secrets.enc/DEK 는 건드리지 않는다.
#   ./scripts/test-init-env.sh            기본 bash 로 실행
#   /bin/bash ./scripts/test-init-env.sh  macOS 기본 bash 3.2 로 실행(호환 확인)
set -uo pipefail
cd "$(dirname "$0")/.."
SRC="$PWD"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
: > "$T/pass"; : > "$T/fail"
ok()  { echo . >> "$T/pass"; printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { echo . >> "$T/fail"; printf '  \033[31m✗\033[0m %s\n' "$1"; }
is()  { local d="$1"; shift; if "$@" >/dev/null 2>&1; then ok "$d"; else bad "$d"; fi; }   # 명령이 0 이면 통과
isnt(){ local d="$1"; shift; if "$@" >/dev/null 2>&1; then bad "$d"; else ok "$d"; fi; }   # 명령이 0 이 아니면 통과
perm() { stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1"; }   # GNU 의 stat -f 는 파일시스템 정보라 -c 를 먼저 시도한다

# 임시 프로젝트를 만들고 P 에 경로를 둔다. 보관함/DEK 도 그 안으로 돌린다.
setup() {
  P="$(mktemp -d "$T/proj.XXXXXX")"
  mkdir -p "$P/scripts/lib"
  cp "$SRC/scripts/init-env.sh" "$SRC/scripts/vault.sh" "$P/scripts/"
  cp "$SRC/scripts/lib/vault.sh" "$SRC/scripts/lib/secrets.sh" "$P/scripts/lib/"
  cp "$SRC/.env.example" "$P/"
  export NOTE_VAULT_FILE="$P/.secrets.enc" NOTE_DEK_FILE="$P/cfg/note/dek.key"
  unset MARIADB_ROOT_PASSWORD MARIADB_PASSWORD REDIS_PASSWORD JUSO_DB_PASSWORD
}
init() { OUT="$( (cd "$P" && "$P/scripts/init-env.sh" "$@" < /dev/null) 2>&1 )"; RC=$?; }   # 터미널 없이 실행
get()  { (cd "$P" && "$P/scripts/vault.sh" get "$1" 2>/dev/null); }
envhas() { grep -Eq "^${1}=.+" "$P/.env"; }
R="rootpw-AAAA1111bbbb2222"; M="mariapw-CCCC3333dddd4444"; D="redispw-EEEE5555ffff6666"

echo "bash $BASH_VERSION"

echo "[예전 방식(.env 평문) → 보관함 이전]"
( setup
  printf 'MARIADB_PORT=9000\nMARIADB_ROOT_PASSWORD=%s\nMARIADB_USER=note\nMARIADB_PASSWORD=%s\nREDIS_PORT=9001\nREDIS_PASSWORD=%s\nJUSO_DB_USER=root\nJUSO_DB_PASSWORD=%s\nJAVA_HOME=/my/jdk\n' "$R" "$M" "$D" "$R" > "$P/.env"
  chmod 600 "$P/.env"
  init
  [ "$RC" = 0 ] && ok "종료 코드 0" || bad "종료 코드 $RC: $OUT"
  ! envhas MARIADB_ROOT_PASSWORD && ! envhas MARIADB_PASSWORD && ! envhas REDIS_PASSWORD && ! envhas JUSO_DB_PASSWORD && ok ".env 에서 평문 비밀번호 4줄이 사라진다" || bad ".env 에 평문이 남음"
  [ "$(get MARIADB_ROOT_PASSWORD)" = "$R" ] && [ "$(get MARIADB_PASSWORD)" = "$M" ] && [ "$(get REDIS_PASSWORD)" = "$D" ] && ok "값이 바뀌지 않고 그대로 보관된다" || bad "보관된 값이 다름"
  grep -q "^JAVA_HOME=/my/jdk" "$P/.env" && grep -q "^MARIADB_PORT=9000" "$P/.env" && grep -q "^JUSO_DB_USER=root" "$P/.env" && ok ".env 의 다른 설정은 그대로다" || bad "다른 설정이 사라짐"
  [ "$(perm "$P/.env")" = 600 ] && ok ".env 권한 600 유지" || bad ".env 권한 $(perm "$P/.env")"
  case "$OUT" in *"$R"*|*"$M"*|*"$D"*) bad "출력에 비밀번호가 찍힘" ;; *) ok "출력에 비밀번호가 찍히지 않는다" ;; esac
  cp "$P/.secrets.enc" "$T/before.enc"
  init
  [ "$RC" = 0 ] && cmp -s "$P/.secrets.enc" "$T/before.enc" && ok "다시 실행해도 안전하다(보관함이 바뀌지 않는다)" || bad "멱등성"
  isnt "임시 파일이 남지 않는다" bash -c "ls '$P'/.env.?????? "
) 2>&1

echo "[보관함에 이미 값이 있을 때]"
( setup
  printf '%s' "$R" | "$P/scripts/vault.sh" set MARIADB_ROOT_PASSWORD 2>/dev/null
  printf 'MARIADB_ROOT_PASSWORD=%s\nMARIADB_PASSWORD=%s\n' "$R" "$M" > "$P/.env"
  init --random
  ! envhas MARIADB_ROOT_PASSWORD && ok "같은 값이면 .env 의 평문만 지운다" || bad "같은 값 평문이 남음"
  [ "$(get MARIADB_ROOT_PASSWORD)" = "$R" ] && ok "그 키의 보관된 값은 그대로다" || bad "보관된 값이 바뀜"
  [ "$(get MARIADB_PASSWORD)" = "$M" ] && ok ".env 에만 있던 다른 키는 옮겨진다" || bad "이전 안 됨"
  [ -n "$(get REDIS_PASSWORD)" ] && ok "없던 키는 --random 으로 채운다" || bad "랜덤 채움 안 됨"
) 2>&1
( setup
  printf '%s' "$R" | "$P/scripts/vault.sh" set MARIADB_ROOT_PASSWORD 2>/dev/null
  printf 'MARIADB_ROOT_PASSWORD=%s\n' "different-value-1234567" > "$P/.env"
  init --random
  envhas MARIADB_ROOT_PASSWORD && ok "값이 다르면 .env 의 줄을 지우지 않는다(어느 쪽이 맞는지 모르므로)" || bad "다른 값 평문을 지움(데이터 손실 위험)"
  [ "$(get MARIADB_ROOT_PASSWORD)" = "$R" ] && ok "보관함 값도 덮어쓰지 않는다" || bad "보관함 값이 바뀜"
  case "$OUT" in *"다릅니다"*) ok "값이 다르다는 경고를 낸다" ;; *) bad "경고 없음: $OUT" ;; esac
) 2>&1

echo "[규칙에 어긋난 예전 평문(예: 8자 미만)]"
( setup
  printf 'MARIADB_ROOT_PASSWORD=%s\nMARIADB_PASSWORD=%s\nREDIS_PASSWORD=redis1\n' "$R" "$M" > "$P/.env"
  init --random
  grep -q '^REDIS_PASSWORD=redis1$' "$P/.env" && ok "규칙에 어긋난 평문은 .env 에 그대로 남는다" || bad ".env 에서 사라짐(DB/Redis 가 쓰는 실제 값을 잃음)"
  [ -z "$(get REDIS_PASSWORD)" ] && ok "그 키는 새 랜덤 값을 만들어 보관하지 않는다(.env 와 보관함이 어긋나는 걸 막는다)" || bad "새 값이 보관됨"
  [ "$(get MARIADB_ROOT_PASSWORD)" = "$R" ] && ok "나머지 키는 정상적으로 이전된다" || bad "나머지 이전 실패"
  case "$OUT" in *"새로 만들지 않습니다"*|*"규칙에 맞지 않아"*) ok "이유를 알려 준다" ;; *) bad "안내 없음: $OUT" ;; esac
) 2>&1

echo "[JUSO_DB_PASSWORD]"
( setup
  printf 'MARIADB_ROOT_PASSWORD=%s\nJUSO_DB_PASSWORD=%s\n' "$R" "custom-juso-pass-9999" > "$P/.env"
  init --random
  envhas JUSO_DB_PASSWORD && ok "root 와 다른 JUSO_DB_PASSWORD 는 .env 에 그대로 둔다" || bad "다른 값을 지움"
  case "$OUT" in *"다릅니다"*) ok "다르다는 경고를 낸다" ;; *) bad "경고 없음" ;; esac
) 2>&1

echo "[새 환경과 입력 방식]"
( setup
  init
  [ "$RC" != 0 ] && case "$OUT" in *"입력할 터미널도 없습니다"*) true ;; *) false ;; esac && ok "터미널도 값도 없으면 중단한다(임의로 만들지 않는다)" || bad "중단하지 않음 또는 다른 이유로 실패: $OUT"
  [ ! -e "$P/.secrets.enc" ] && ok "그때 보관함이 만들어지지 않는다" || bad "보관함이 만들어짐"
  [ -f "$P/.env" ] && ! grep -q "PASSWORD" "$P/.env" && ok ".env 는 비밀 없이 만들어진다" || bad ".env 문제"
) 2>&1
( setup
  init --random
  n=0; for k in MARIADB_ROOT_PASSWORD MARIADB_PASSWORD REDIS_PASSWORD; do v="$(get $k)"; [ "${#v}" = 24 ] && n=$((n+1)); done
  [ "$n" = 3 ] && ok "--random: 3개 모두 24자 랜덤으로 보관된다" || bad "--random 결과 ($n/3)"
  ! grep -q "PASSWORD=" "$P/.env" && ok "--random 후에도 .env 에는 비밀번호가 없다" || bad ".env 에 비밀번호"
) 2>&1
( setup
  OUT="$( (cd "$P" && MARIADB_ROOT_PASSWORD="$R" MARIADB_PASSWORD="$M" REDIS_PASSWORD="$D" "$P/scripts/init-env.sh" < /dev/null) 2>&1 )"
  [ "$(get MARIADB_ROOT_PASSWORD)" = "$R" ] && [ "$(get REDIS_PASSWORD)" = "$D" ] && ok "환경변수로 준 값을 보관한다" || bad "환경변수 값 보관 실패"
) 2>&1
( setup
  OUT="$( (cd "$P" && MARIADB_ROOT_PASSWORD='has space 1234' MARIADB_PASSWORD="$M" REDIS_PASSWORD="$D" "$P/scripts/init-env.sh" < /dev/null) 2>&1 )"; RC=$?
  [ "$RC" != 0 ] && [ -z "$(get MARIADB_ROOT_PASSWORD)" ] && case "$OUT" in *"공백"*) true ;; *) false ;; esac && ok "규칙에 어긋난 환경변수 값은 거부한다" || bad "규칙 위반 값이 보관됨 또는 다른 이유로 실패: $OUT"
) 2>&1

echo "[손으로 고친 예전 .env: 따옴표, CRLF, 줄 끝 공백, 같은 키가 여러 줄, 빈 값]"
( setup
  printf 'MARIADB_ROOT_PASSWORD="%s"\nMARIADB_PASSWORD='"'"'%s'"'"'\nREDIS_PASSWORD=%s   \n' "$R" "$M" "$D" > "$P/.env"
  init --random
  [ "$(get MARIADB_ROOT_PASSWORD)" = "$R" ] && [ "$(get MARIADB_PASSWORD)" = "$M" ] && [ "$(get REDIS_PASSWORD)" = "$D" ] && ok "따옴표로 감싼 값과 줄 끝 공백은 벗겨서(bash/compose 가 읽는 값 그대로) 옮긴다" || bad "따옴표/공백 이전: $OUT"
  ! envhas MARIADB_ROOT_PASSWORD && ! envhas MARIADB_PASSWORD && ! envhas REDIS_PASSWORD && ok "옮긴 뒤 .env 에서 지운다" || bad ".env 에 평문이 남음"
) 2>&1
( setup
  printf 'MARIADB_ROOT_PASSWORD=%s\r\nMARIADB_PASSWORD=%s\r\nREDIS_PASSWORD=%s\r\nJAVA_HOME=/my/jdk\r\n' "$R" "$M" "$D" > "$P/.env"
  init --random
  [ "$(get MARIADB_ROOT_PASSWORD)" = "$R" ] && [ "$(get REDIS_PASSWORD)" = "$D" ] && ok "CRLF 줄바꿈의 값도 CR 없이 옮긴다" || bad "CRLF 이전: $OUT"
  grep -q "^JAVA_HOME=/my/jdk" "$P/.env" && ok "CRLF 파일의 다른 설정은 그대로다" || bad "다른 설정이 사라짐"
) 2>&1
( setup
  printf 'REDIS_PASSWORD=%s\nREDIS_PASSWORD=%s\nMARIADB_ROOT_PASSWORD=\nMARIADB_PASSWORD=""\n' "old-redis-0000" "$D" > "$P/.env"
  init --random
  [ "$(get REDIS_PASSWORD)" = "$D" ] && ok "같은 키가 여러 줄이면 마지막 값(bash/compose 가 쓰는 값)을 옮긴다" || bad "중복 키 이전: $OUT"
  [ "$(grep -c '^REDIS_PASSWORD=' "$P/.env")" = 0 ] && ok "중복된 줄이 모두 지워진다" || bad "중복 줄이 남음"
  [ -n "$(get MARIADB_ROOT_PASSWORD)" ] && [ -n "$(get MARIADB_PASSWORD)" ] && ok "값이 빈 키(MARIADB_ROOT_PASSWORD=, \"\")는 이전할 것이 없으므로 새로 채운다" || bad "빈 값 키 처리: $OUT"
) 2>&1
( setup
  printf 'MARIADB_ROOT_PASSWORD=short\nMARIADB_PASSWORD=x\nREDIS_PASSWORD=y\n' > "$P/.env"
  init --random
  case "$OUT" in *"이미 모두 있습니다"*) bad "아무것도 보관하지 못했는데 이미 모두 있다고 알림" ;; *"보관하지 못한 비밀번호"*) ok "하나도 못 옮겼으면 보관함에 이미 있다고 하지 않고 알린다" ;; *) bad "안내 없음: $OUT" ;; esac
  [ -z "$(get MARIADB_ROOT_PASSWORD)" ] && grep -q '^MARIADB_ROOT_PASSWORD=short$' "$P/.env" && ok "값은 .env 에 그대로 남는다" || bad "값 손실"
) 2>&1

echo "[손으로 고친 예전 .env: export 접두어, 앞 공백, 인라인 주석]"
( setup
  printf 'export MARIADB_ROOT_PASSWORD=%s\n  MARIADB_PASSWORD=%s\nREDIS_PASSWORD=%s # 메모\nexport MARIADB_PORT=9000\n' "$R" "$M" "$D" > "$P/.env"
  init --random
  [ "$(get MARIADB_ROOT_PASSWORD)" = "$R" ] && [ "$(get MARIADB_PASSWORD)" = "$M" ] && [ "$(get REDIS_PASSWORD)" = "$D" ] && ok "export 접두어, 앞 공백, 인라인 주석이 붙은 값도 bash/compose 가 읽는 값 그대로 옮긴다" || bad "export/공백/주석 이전: $OUT"
  ! grep -Eq 'PASSWORD=' "$P/.env" && ok "옮긴 뒤 그 줄들이 .env 에서 지워진다" || bad ".env 에 평문이 남음"
  [ "$(grep -c 'MARIADB_PORT=' "$P/.env")" = 1 ] && ok "export 로 적은 설정 키는 .env.example 값으로 중복 추가하지 않는다" || bad "MARIADB_PORT 중복"
) 2>&1
( setup
  printf "MARIADB_ROOT_PASSWORD=\"%s\" # 메모\nMARIADB_PASSWORD='%s'  # x\nREDIS_PASSWORD=%s#tail1\n" "$R" "$M" "$D" > "$P/.env"
  init --random
  [ "$(get MARIADB_ROOT_PASSWORD)" = "$R" ] && [ "$(get MARIADB_PASSWORD)" = "$M" ] && ok "따옴표 뒤의 인라인 주석은 값이 아니다" || bad "따옴표+주석 이전: $OUT"
  [ "$(get REDIS_PASSWORD)" = "${D}#tail1" ] && ok "공백 없이 붙은 #은 주석이 아니라 값의 일부다" || bad "붙은 # 처리"
) 2>&1
( setup
  printf 'export REDIS_PASSWORD=short\n' > "$P/.env"
  init --random
  [ -z "$(get REDIS_PASSWORD)" ] && grep -q 'REDIS_PASSWORD=short' "$P/.env" && ok "export 로 적은 규칙 위반 값도 새 값으로 덮지 않고 .env 에 남긴다" || bad "export 줄을 못 보고 새 값을 만듦"
) 2>&1

echo "[--ask 와 --help]"
( setup
  printf 'MARIADB_ROOT_PASSWORD=%s\n' "$R" > "$P/.env"
  OUT="$( (cd "$P" && MARIADB_ROOT_PASSWORD="new-root-pass-1234" MARIADB_PASSWORD="$M" REDIS_PASSWORD="$D" "$P/scripts/init-env.sh" --ask < /dev/null) 2>&1 )"
  [ "$(get MARIADB_ROOT_PASSWORD)" = "new-root-pass-1234" ] && ok "--ask: 새 값으로 덮어쓴다" || bad "--ask 덮어쓰기"
  envhas MARIADB_ROOT_PASSWORD && ok "--ask: .env 의 예전 평문은 지우지 않는다(기존 볼륨의 유일한 기록일 수 있음)" || bad "--ask 가 평문을 지움"
  case "$OUT" in *"우선 씁니다"*) ok "--ask: 예전 평문이 우선된다는 경고를 낸다" ;; *) bad "--ask 경고 없음: $OUT" ;; esac
) 2>&1
( setup
  OUT="$( (cd "$P" && MARIADB_ROOT_PASSWORD="$R" MARIADB_PASSWORD="$M" REDIS_PASSWORD="$D" "$P/scripts/init-env.sh" < /dev/null) 2>&1 )"
  OUT="$( (cd "$P" && MARIADB_ROOT_PASSWORD="new-root-pass-1234" "$P/scripts/init-env.sh" --ask < /dev/null) 2>&1 )"; RC=$?
  [ "$RC" != 0 ] && [ "$(get MARIADB_ROOT_PASSWORD)" = "$R" ] && [ "$(get MARIADB_PASSWORD)" = "$M" ] && ok "--ask 가 도중에 중단되면 보관함은 하나도 바뀌지 않는다(예전 값을 잃지 않는다)" || bad "--ask 중단 시 일부만 덮어씀 (rc=$RC)"
) 2>&1
( setup
  OUT="$( (cd "$P" && "$P/scripts/init-env.sh" --help) 2>&1 )"
  case "$OUT" in *"set -euo"*|*'ROOT='*|*'cd "$ROOT"'*) bad "--help 에 코드 줄이 섞임" ;; *) ok "--help 출력에 코드가 섞이지 않는다" ;; esac
  case "$OUT" in *"--random"*"--ask"*) ok "--help 가 옵션을 설명한다" ;; *) bad "--help 설명 없음" ;; esac
  OUT="$( (cd "$P" && "$P/scripts/init-env.sh" --nope) 2>&1 )"; RC=$?
  [ "$RC" != 0 ] && ok "알 수 없는 옵션은 거부한다" || bad "알 수 없는 옵션 허용"
) 2>&1

echo "[load_secrets_env / warn_plaintext_env]"
( setup
  printf '%s' "$R" | "$P/scripts/vault.sh" set MARIADB_ROOT_PASSWORD 2>/dev/null
  printf '%s' "$M" | "$P/scripts/vault.sh" set MARIADB_PASSWORD 2>/dev/null
  printf '%s' "$D" | "$P/scripts/vault.sh" set REDIS_PASSWORD 2>/dev/null
  res="$(bash -c "cd '$P'; source scripts/lib/secrets.sh; load_secrets_env; printf '%s|%s|%s|%s' \"\$MARIADB_ROOT_PASSWORD\" \"\$MARIADB_PASSWORD\" \"\$REDIS_PASSWORD\" \"\$JUSO_DB_PASSWORD\"" 2>&1)"
  [ "$res" = "$R|$M|$D|$R" ] && ok "보관함의 값을 환경변수로 올리고 JUSO_DB_PASSWORD 는 root 비밀번호로 채운다" || bad "환경변수 올리기: $res"
  res="$(REDIS_PASSWORD=from-env-9999 bash -c "cd '$P'; source scripts/lib/secrets.sh; load_secrets_env; printf '%s' \"\$REDIS_PASSWORD\"" 2>&1)"
  [ "$res" = "from-env-9999" ] && ok "이미 환경에 있는 값이 보관함보다 우선한다" || bad "환경변수 우선순위: $res"
  res="$(JUSO_DB_PASSWORD=juso-custom-9999 bash -c "cd '$P'; source scripts/lib/secrets.sh; load_secrets_env; printf '%s' \"\$JUSO_DB_PASSWORD\"" 2>&1)"
  [ "$res" = "juso-custom-9999" ] && ok "직접 지정한 JUSO_DB_PASSWORD 는 덮어쓰지 않는다" || bad "JUSO 우선순위: $res"
  # 못 푸는 보관함: 이유를 알리되 실패(종료)하지는 않는다
  printf 'wrong-dek\n' > "$NOTE_DEK_FILE"
  err="$(bash -c "cd '$P'; source scripts/lib/secrets.sh; load_secrets_env; echo rc=\$?" 2>&1)"
  case "$err" in *"복호화"*"rc=0"*|*"DEK"*"rc=0"*) ok "못 푸는 보관함: 이유를 알리고 0 을 돌려준다" ;; *) bad "못 푸는 보관함 처리: $err" ;; esac
  case "$err" in *"$R"*) bad "오류 메시지에 비밀번호" ;; *) ok "그 메시지에 비밀번호가 없다" ;; esac
) 2>&1
( setup
  err="$(bash -c "cd '$P'; source scripts/lib/secrets.sh; load_secrets_env; echo rc=\$?" 2>&1)"
  [ "$err" = "rc=0" ] && ok "보관함이 없으면 조용히 0 을 돌려준다" || bad "보관함 없음 처리: $err"
  [ ! -e "$NOTE_DEK_FILE" ] && ok "읽기만 해서는 DEK 가 만들어지지 않는다" || bad "DEK 가 만들어짐"
  printf 'MARIADB_ROOT_PASSWORD=%s\nREDIS_PASSWORD=\nJUSO_DB_PASSWORD=%s\nMARIADB_PORT=9000\n' "$R" "$R" > "$P/.env"
  w="$(bash -c "cd '$P'; source scripts/lib/secrets.sh; warn_plaintext_env .env" 2>&1)"
  case "$w" in *MARIADB_ROOT_PASSWORD*JUSO_DB_PASSWORD*) ok "평문이 남은 키 이름을 알린다" ;; *) bad "경고 없음: $w" ;; esac
  case "$w" in *"$R"*) bad "경고에 비밀번호 값이 찍힘" ;; *) ok "경고에 값은 찍지 않는다" ;; esac
  case "$w" in *REDIS_PASSWORD*) bad "값이 빈 줄까지 경고함" ;; *) ok "값이 빈 줄은 경고하지 않는다" ;; esac
) 2>&1

echo "[비슷한 접두어 키, 주석 줄, 빈 값 표기]"
( setup
  printf '# MARIADB_PASSWORD=%s\nMARIADB_PASSWORD_OLD=oldoldold1\nREDIS_PASSWORD_FILE=/run/x\nMARIADB_ROOT_PASSWORD=%s\n' "$M" "$R" > "$P/.env"
  init --random
  [ "$(get MARIADB_ROOT_PASSWORD)" = "$R" ] && ok "이전 대상은 정확한 키 이름의 줄뿐이다" || bad "이전 실패: $OUT"
  [ "$(get MARIADB_PASSWORD)" != "$M" ] && ok "주석 줄(# KEY=값)은 값으로 읽지 않는다" || bad "주석 줄의 값을 보관함"
  grep -q '^MARIADB_PASSWORD_OLD=oldoldold1$' "$P/.env" && grep -q '^REDIS_PASSWORD_FILE=/run/x$' "$P/.env" && grep -q '^# MARIADB_PASSWORD=' "$P/.env" && ok "비슷한 접두어 키와 주석 줄은 지우지 않는다" || bad "다른 줄을 지움"
  w="$(bash -c "cd '$P'; source scripts/lib/secrets.sh; warn_plaintext_env .env" 2>&1)"
  [ -z "$w" ] && ok "비슷한 접두어 키와 주석 줄은 평문 비밀번호로 경고하지 않는다" || bad "잘못된 경고: $w"
) 2>&1
( setup
  printf 'REDIS_PASSWORD=""\nMARIADB_PASSWORD=   \nMARIADB_ROOT_PASSWORD= # 메모\nJUSO_DB_PASSWORD=\r\n' > "$P/.env"
  w="$(bash -c "cd '$P'; source scripts/lib/secrets.sh; warn_plaintext_env .env" 2>&1)"
  [ -z "$w" ] && ok "값이 비었다고 읽히는 줄(빈 따옴표, 공백뿐, 주석뿐, CR 뿐)은 경고하지 않는다(init-env.sh 가 지울 수 없는 경고를 영원히 내지 않는다)" || bad "빈 값에 경고: $w"
) 2>&1
( setup
  printf 'REDIS_PORT=9001\nexport MARIADB_PORT=9000\nREDIS_PASSWORD=""\nMARIADB_PASSWORD=abcdefgh # x\nJUSO_DATA_FILE=\n' > "$P/.env"
  eval "$(sed -n '/^env_has()/p' "$SRC/docker/manage.sh")"
  ENV_FILE="$P/.env"; . "$P/scripts/lib/secrets.sh"
  ( env_has REDIS_PORT && env_has MARIADB_PORT ) && ok "manage.sh env_has: 비밀 아닌 설정(포트)은 .env 에서 읽는다(export 접두어 포함)" || bad "env_has 포트"
  ( ! env_has REDIS_PASSWORD && ! env_has JUSO_DATA_FILE && ! env_has NOPE_KEY ) && ok "manage.sh env_has: 값이 빈 줄과 없는 키는 없는 것으로 본다" || bad "env_has 빈 값"
  ( env_has MARIADB_PASSWORD ) && ok "manage.sh env_has: 인라인 주석이 붙은 값은 있는 것으로 본다" || bad "env_has 주석"
  ( REDIS_PASSWORD=fromenv1; export REDIS_PASSWORD; env_has REDIS_PASSWORD ) && ok "manage.sh env_has: 환경변수(보관함에서 올린 값)도 센다" || bad "env_has 환경변수"
) 2>&1
( setup
  # NUL 이 든 줄이나 잘못된 UTF-8 이 있어도 값을 정확히 읽는다(grep 의 "Binary file ... matches" 가 값이 되거나 sed 가 빈 값을 내지 않는다).
  printf 'MARIADB_PORT=9\0000\nREDIS_PASSWORD=%s\nMARIADB_PASSWORD=ab\377cdefgh\nMARIADB_ROOT_PASSWORD=\377\376\n' "$D" > "$P/.env"
  v="$(bash -c "cd '$P'; source scripts/lib/secrets.sh; env_file_value .env REDIS_PASSWORD")"
  [ "$v" = "$D" ] && ok "env_file_value: NUL 이 든 줄이 .env 에 있어도 다른 키의 값은 그대로 읽는다" || bad "NUL 파일의 값: $v"
  v="$(bash -c "cd '$P'; source scripts/lib/secrets.sh; env_file_value .env MARIADB_PORT")"
  case "$v" in *Binary*) bad "Binary file 메시지가 값이 됨: $v" ;; *) ok "env_file_value: grep 의 Binary file 메시지를 값으로 내지 않는다" ;; esac
  v="$(LC_ALL=en_US.UTF-8 bash -c "cd '$P'; source scripts/lib/secrets.sh; env_file_value .env MARIADB_PASSWORD | wc -c | tr -d ' '")"
  [ "$v" = 9 ] && ok "env_file_value: 잘못된 UTF-8 바이트가 든 값도 빈 값으로 읽지 않는다" || bad "잘못된 UTF-8 값 길이: $v"
) 2>&1
( setup
  # NUL 이 든 .env 에서 평문을 지울 때 .env 가 "Binary file .env matches" 한 줄로 바뀌어 다른 설정을 잃지 않는다.
  printf 'MARIADB_PORT=9\0000\nREDIS_PASSWORD=%s\nJAVA_HOME=/my/jdk\nX=\377\n' "$D" > "$P/.env"
  init --random
  [ "$RC" = 0 ] && [ "$(get REDIS_PASSWORD)" = "$D" ] && ok "NUL 이 든 .env: 평문 이전이 정상 종료하고 값이 보관된다" || bad "NUL .env 이전 RC=$RC: $OUT"
  grep -aq '^JAVA_HOME=/my/jdk$' "$P/.env" && grep -aq '^MARIADB_PORT=9' "$P/.env" && ! grep -aq 'Binary file' "$P/.env" && ! grep -aq '^REDIS_PASSWORD=' "$P/.env" && ok "NUL 이 든 .env: 평문 줄만 지우고 나머지 설정은 그대로다" || bad ".env 가 망가짐: $(tr -d '\000' < "$P/.env" | head -c 200)"
) 2>&1
( setup
  printf 'export REDIS_PASSWORD=%s\n' "$D" > "$P/.env"
  OUT="$( (cd "$P" && MARIADB_ROOT_PASSWORD="$R" MARIADB_PASSWORD="$M" REDIS_PASSWORD="new-redis-pass-1234" "$P/scripts/init-env.sh" --ask < /dev/null) 2>&1 )"
  case "$OUT" in *"우선 씁니다"*) ok "--ask: export 로 적은 예전 평문도 우선 적용된다는 경고를 낸다" ;; *) bad "--ask export 경고 없음: $OUT" ;; esac
) 2>&1

pass="$(wc -l < "$T/pass" | tr -d ' ')"; fail="$(wc -l < "$T/fail" | tr -d ' ')"
echo; echo "결과: 통과 $pass / 실패 $fail"
[ "$fail" -eq 0 ]
