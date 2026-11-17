# note

Vue + Spring Boot로 여러 주제를 공부하고, 작은 프로젝트를 직접 만들어 보는 **스터디용 프로젝트**입니다.
공부하고 기록해 두는 용도로, 배운 내용은 이 저장소에 코드와 문서로 쌓아 갑니다.

## 구조

```
note/
├── .env.example     환경변수 견본 (복사해서 .env 를 만든다. .env 는 커밋되지 않음)
├── backend/         Gradle 멀티 프로젝트 (MSA, Spring Boot 3.5 / Java 25)
│   ├── gateway/         Spring Cloud Gateway (9203) — 모든 요청의 진입점
│   ├── juso-service/    도로명주소 대용량 적재 API + STOMP 웹소켓 (9204)
│   ├── image-service/   이미지 캐싱 실험 API (9205, Redis)
│   ├── catch-service/   캐치마인드 그림 맞추기 (9206, STOMP 웹소켓)
│   ├── rag-service/     RAG 검색 실험 API (9207, 임베딩 모델을 서비스 안에서 실행)
│   └── toast-service/   claude-toast: Claude Code 작업 알림을 브라우저 토스트로 (9208, STOMP 웹소켓)
├── frontend/        Vue 3 + TypeScript + Pinia (Vite, 9201) — 좌측 메뉴 + 대용량 적재 화면 (e2e/ 는 Playwright 화면 테스트)
├── generate-data/   대용량 테스트 데이터 생성 (도로명주소 → result/202610_juso.txt)
├── docker/          인프라 컨테이너 모음 (mariadb, redis, 이후 mqtt/kafka 등) + manage.sh
├── scripts/         .env 만들기, 백엔드/프론트 실행 스크립트, claude-toast 설치(claude-toast/)
└── docs/            스터디 노트 (주제별 정리)
```

## 기술 스택

| 영역 | 사용 기술 |
|---|---|
| Backend | Java 25, Spring Boot 3.5, Spring Cloud Gateway, JDBC / MyBatis / Spring Data JPA, STOMP(WebSocket), Spring Data Redis(Lettuce), MariaDB 11.4, Redis 7 |
| Frontend | Vue 3, TypeScript, Pinia, vue-router, highlight.js, Vite 5 |
| Build | Gradle (wrapper 포함), npm |

## 어떤 파일을 어디에 둬야 하나

저장소에 올라가지 않는 파일(비밀번호, 대용량 데이터)은 직접 만들어 아래 위치에 둡니다.

| 파일 | 위치 | 만드는 법 | 비고 |
|---|---|---|---|
| `.env` | 프로젝트 **루트** (`note/.env`) | `scripts/init-env.sh` 로 `.env.example` 을 복사하고 비밀번호를 직접 입력(엔터는 랜덤) | 커밋 금지(`.gitignore`). DB 계정/비밀번호, JDK 위치, 데이터 파일 경로 |
| `202610_juso.txt` | `generate-data/result/` | `python3 generate-data/generate_juso.py` | 964MB, 커밋 금지. DB 컨테이너가 `/import` 로 같은 폴더를 읽는다 |
| MariaDB 데이터 | 컨테이너 볼륨 `mariadb-data` | `docker/manage.sh up mariadb` | 최초 기동 시 `docker/mariadb/initdb/01-schema.sql` 자동 적용 |
| 임베딩 모델 | 프로젝트 루트 `rag-data/model/` | RAG 검색 화면의 "모델 받고 준비하기" 버튼 | 커밋 금지, 재내려받기 가능(약 135MB) |
| 상품 이미지 | 프로젝트 루트 `image-data/` | 이미지 캐싱 화면의 "이미지 만들기" 버튼 | 커밋 금지, 재생성 가능(1,000개 약 10초, 76MB) |

`.env` 에 들어가는 값(자세한 설명은 `.env.example`):

| 키 | 용도 |
|---|---|
| `MARIADB_PORT`, `MARIADB_DATABASE`, `MARIADB_USER` | DB 컨테이너 설정 (기본 9000, juso, note) |
| `MARIADB_ROOT_PASSWORD`, `MARIADB_PASSWORD` | DB 컨테이너 비밀번호 — **반드시 채운다** |
| `JUSO_DB_URL`, `JUSO_DB_USER`, `JUSO_DB_PASSWORD` | juso-service 가 접속할 DB. 비밀번호는 `MARIADB_ROOT_PASSWORD` 와 같은 값 |
| `JUSO_DATA_FILE` | 적재할 원본 파일(절대 경로). 비우면 `generate-data/result/202610_juso.txt` |
| `REDIS_PORT`, `REDIS_PASSWORD` | Redis 컨테이너와 image-service 접속 정보 (기본 포트 9001) — 비밀번호는 **반드시 채운다** |
| `IMAGE_DIR` | 상품 이미지 폴더(절대 경로). 비우면 `image-data/` |
| `IMAGE_SAMPLE_URL`, `IMAGE_SAMPLE_THREADS` | (선택) "샘플 사진 받기" 주소 템플릿과 동시 다운로드 수. 기본은 picsum.photos, 8개 |
| `RAG_DOCS_DIR`, `RAG_MODEL_DIR`, `RAG_DOCS_EXCLUDE` | (선택) RAG 검색 대상 문서 폴더, 모델 저장 폴더, 검색 대상에서 뺄 파일. 비우면 `docs/`, `rag-data/model/`, `rag.md` |
| `JAVA_HOME` | `java` 가 PATH 에 없을 때 JDK 25 위치 |

코드와 설정 파일(`application.yml`, `docker-compose.yml`)에는 비밀번호를 적지 않습니다. 비어 있으면 실행이 중단됩니다.

## 실행 방법

필요한 것: JDK 25, Node.js 18+, docker(또는 podman)

macOS 에서는 docker 가 없으면 colima 로 대신합니다. JDK 25 는 `JAVA_HOME` 을 비워 두면 OS 별로 자동으로 찾습니다(`scripts/lib/platform.sh`).

```bash
brew install colima docker docker-compose
colima start
```

```bash
# 0) 처음 한 번: .env 만들고 비밀번호 채우기, 데이터 준비
scripts/init-env.sh                       # note/.env 생성 → 비밀번호를 물어봄 (엔터는 랜덤, 전부 랜덤은 --random)
python3 generate-data/generate_juso.py    # generate-data/result/202610_juso.txt

# 1) DB (mariadb: localhost:9000, DB juso), 캐시(redis: localhost:9001)
docker/manage.sh up mariadb
docker/manage.sh up redis                 # 이미지 캐싱 화면에서만 필요

# 2) 백엔드 — 터미널을 나눠 각각 실행 (.env 를 읽어 환경변수로 넘겨 줌)
scripts/run-backend.sh juso               # http://localhost:9204
scripts/run-backend.sh image              # http://localhost:9205 (이미지 캐싱 화면에서만 필요)
scripts/run-backend.sh catch              # http://localhost:9206 (캐치마인드 화면에서만 필요)
scripts/run-backend.sh rag                # http://localhost:9207 (RAG 검색 화면에서만 필요, 결과 저장용 mariadb 필요)
scripts/run-backend.sh toast              # http://localhost:9208 (claude-toast. 상시로 켜 두려면 scripts/claude-toast/install.sh)
scripts/run-backend.sh gateway            # http://localhost:9203 (진입점)

# 3) 프론트엔드 (http://localhost:9201)
scripts/run-frontend.sh
```

요청 흐름: `Vue(9201) → /api, /ws-juso 프록시 → gateway(9203) → juso-service(9204)`. 프론트는 gateway 주소만 알면 됩니다.
브라우저에서 `http://localhost:9201` 을 열고 왼쪽 메뉴의 DB → 대용량 적재로 들어갑니다.

- 적재할 방식을 체크해 순서대로 실행하고, 진행 메시지는 우측 하단 웹소켓 아이콘에서 볼 수 있습니다.
- 적재 방식 비교와 노하우: [docs/bulk-insert.md](docs/bulk-insert.md)
- 이미지 캐싱(Redis) 실험과 결과: [docs/image-cache.md](docs/image-cache.md)
- 이미지 캐싱(캐시 없음 / 미리 만든 이미지 / Redis)은 왼쪽 메뉴 캐시 → 이미지 캐싱. 조건을 화면에서 바꿔 가며 실험합니다.
- RAG 검색(청킹·임베딩·키워드/벡터/하이브리드 검색 정답률 비교)과 왜 하는지: [docs/rag.md](docs/rag.md). 모르는 용어는 왼쪽 메뉴 AI → AI 용어 정리
- 수박 게임(Canvas + 직접 구현한 2D 물리) 만든 과정: [docs/suika-game.md](docs/suika-game.md)

## 화면 테스트 (Playwright)

웹 화면은 빌드가 통과하는 것만으로는 알 수 없어서, 실제 브라우저(Chromium)로 띄워 동작과 모양을 확인합니다. 테스트는 `frontend/e2e/` 에 있습니다.

```bash
cd frontend
npx playwright install chromium   # 처음 한 번 (브라우저 내려받기, 약 100MB)
npm run e2e                       # 전체 실행
npx playwright test -g "토스트"    # 이름에 "토스트"가 들어간 테스트만
```

- **미리 떠 있어야 하는 것**: 백엔드(gateway 와 각 서비스)와 toast-service(9208). 프론트 dev 서버(9201)만 없으면 테스트가 띄웁니다. 서비스가 하나라도 꺼져 있으면 해당 화면 테스트가 실패하는데, 의도한 동작입니다(꺼진 서비스를 알려 줍니다).
- **무엇을 보나** (28개)
  - `pages.spec.ts`: 모든 화면이 열리고 제목이 맞는지, 열리는 동안 콘솔 오류·미처리 예외·서버 오류(5xx)가 없는지, 사이드 메뉴로 이동되는지. **새 화면을 만들면 이 파일의 `pages` 목록에 추가합니다.**
  - `toast.spec.ts`: 토스트가 뜨고 누르면 닫히는지(닫기 버튼 없음), 키보드(Esc, Enter), 글자 선택 중에는 안 닫히는지, 같은 프로젝트·종류의 교체, 최대 5개, 완료는 10초 뒤 사라지고 확인 필요는 남는지, 탭이 가려져 있는 동안은 완료도 남고 다시 보일 때부터 10초를 새로 세는지, HTML 이 실행되지 않는지, "브라우저 알림 허용" 버튼, 탭이 가려져 있을 때의 브라우저(OS) 알림, 라이트·다크·좁은 화면의 모양.
  - `toast-api.spec.ts`: toast-service 의 입력 규칙(JSON 만, 잘못된 `kind` 는 400, 이상한 `Host` 는 403).
- **스크린샷**: 모양 테스트가 `frontend/test-results/screens/toast-light.png`, `toast-dark.png`, `toast-mobile.png` 를 남깁니다. 실패한 테스트는 스크린샷과 추적(trace, `npx playwright show-trace <trace.zip>`)을 `test-results/` 에 남깁니다. 모두 git 에 올라가지 않습니다.
- **주의**
  - 토스트 테스트는 실제 toast-service 로 알림을 보내므로, 이미 열어 둔 Note 탭에도 토스트가 뜹니다(새로고침하면 사라집니다).
  - 적재 실행이나 삭제처럼 데이터를 바꾸는 버튼은 누르지 않습니다. 화면을 열고 읽는 것만 합니다.
  - 애니메이션 도중에 위치를 재거나 스크린샷을 찍으면 값이 어긋나므로, 해당 테스트는 `reducedMotion: 'reduce'`(동작 줄이기)로 고정합니다. 시간이 지나야 일어나는 동작(10초 뒤 닫힘)은 `page.clock` 으로 시간을 건너뛰어 확인합니다.
  - 토스트를 받는 화면이 구독을 마쳤는지는 `/api/toast/status` 의 구독자 수가 늘어나는 것으로 확인합니다(이미 열려 있는 다른 탭의 구독은 세지 않습니다).

## claude-toast (Claude Code 작업 알림)

Claude Code 가 작업을 끝냈거나 확인을 기다릴 때, 브라우저 화면 오른쪽 위에 토스트를 띄웁니다. 터미널이 아니라 브라우저를 보고 있어도 알 수 있습니다.
**어느 프로젝트에서 Claude Code 를 쓰든** 동작합니다(프로젝트 이름이 토스트에 표시됩니다).

```
Claude Code 훅 ──HTTP POST──▶ toast-service(9208) ──STOMP /topic/toast──▶ 브라우저(Note 어느 화면이든) 토스트
(응답 끝남 / 확인 필요)         127.0.0.1 에만 열림     vite 프록시 /ws-toast
```

- **훅**: Claude Code 가 정해진 순간에 사용자가 등록한 명령을 자동으로 실행하는 기능입니다. `Stop`(응답이 끝남)과 `Notification`(권한·입력이 필요함)에 `notify.py` 를 걸어 두었습니다. 모델이 알림을 보내는 걸 기억할 필요가 없습니다.
- **서버**: `toast-service`(Spring Boot, STOMP 내장 브로커). 받은 알림을 저장하지 않고 바로 전달합니다. 다른 서비스(gateway, DB)와 무관하게 혼자 뜹니다.
- **브라우저**: `App.vue` 에서 한 번 연결해 어느 화면이든 받습니다. 완료·정보는 10초 뒤 사라지고(탭이 가려져 있는 동안은 세지 않고, 다시 보일 때부터 셉니다. 다른 스페이스에 있다 돌아와도 놓치지 않게), 확인 필요·오류는 직접 닫을 때까지 남습니다. 사이드 메뉴 아래의 "브라우저 알림 허용"을 누르면, 다른 탭이나 다른 스페이스를 보고 있을 때 OS 알림도 뜹니다.

### 설치 (컴퓨터마다 한 번)

```bash
scripts/claude-toast/install.sh              # 빌드 + 로그인 때 자동 시작되는 서비스 등록 + Claude Code 훅 등록
scripts/claude-toast/install.sh --uninstall  # 서비스와 훅 제거
```

- 서비스: macOS 는 launchd(`~/Library/LaunchAgents/com.ethan.claude-toast.plist`), Linux 는 systemd `--user`. 죽으면 다시 켜집니다. 로그는 macOS `~/Library/Logs/claude-toast.log`, Linux `journalctl --user -u claude-toast`.
- 훅: 사용자 전역 설정(`~/.claude/settings.json`)에 우리 훅만 추가합니다. 기존 설정은 그대로 두고, 처음 한 번 `settings.json.bak-claude-toast` 로 백업합니다. 설정을 저장하면 **실행 중인 세션에도 바로 적용됩니다**(이 환경에서 확인: 설치한 세션에서 훅이 곧바로 발동했습니다). 적용되지 않는 버전이라면 Claude Code 를 새로 시작하세요.
- 실행에 필요한 파일(jar, 스크립트)은 `~/.local/share/claude-toast/` 로 복사해서 씁니다. macOS 의 백그라운드 서비스는 `~/Desktop` 같은 보호 폴더를 읽을 수 없어서(`Operation not permitted`), 저장소 위치와 상관없이 돌게 하려는 것입니다. **코드를 바꾼 뒤에는 `install.sh` 를 다시 실행**해야 반영됩니다.
- Linux 의 systemd 사용자 서비스는 로그인해 있을 때만 돕니다. 로그아웃 뒤에도 켜 두려면 `loginctl enable-linger $USER` 를 실행하세요. (Linux 는 아직 실제로 검증하지 못했습니다.)
- 브라우저에서 Note(`http://localhost:9201`)를 열어 두어야 토스트가 뜹니다. 주소가 `localhost`/`127.0.0.1` 이 아니면(예: 공유기 IP) 연결이 거부됩니다.

### 직접 보내기

```bash
curl -X POST localhost:9208/api/toast -H 'Content-Type: application/json' \
  -d '{"kind":"done","title":"빌드 끝","message":"테스트 통과","project":"note"}'
curl localhost:9208/api/toast/status     # {"subscribers":1} = 토스트를 받는 브라우저 연결 수
```

`kind` 는 `done` · `attention` · `error` · `info`(기본). `title`, `message`, `project` 는 선택입니다. 응답의 `subscribers` 가 0 이면 받을 브라우저가 열려 있지 않은 것입니다.

### 설정과 보안

- 짧은 대화에 일일이 알리지 않도록, **15초보다 빨리 끝난 응답은 알리지 않습니다.** 바꾸려면 훅 명령의 환경변수 `CLAUDE_TOAST_MIN_SECONDS`(0 이면 항상 알림)를 지정합니다. (`~/.claude/settings.json` 의 우리 훅 `command` 앞에 `CLAUDE_TOAST_MIN_SECONDS=0 ` 을 붙입니다. `install.sh` 를 다시 실행하면 우리 훅을 새로 쓰므로 이 수정이 사라지니, 다시 붙이세요.)
- 입력 없이 60초가 지나면 오는 "입력을 기다리는 중" 알림은 자주 와서 **건너뜁니다**(작업 완료는 `Stop` 훅이 따로 알립니다). 받고 싶으면 `CLAUDE_TOAST_IDLE=1` 을 지정합니다.
- 같은 프로젝트의 같은 종류 알림은 쌓이지 않고 최신 것 하나로 바뀝니다. 토스트는 메모리에만 있어서 새로고침하면 사라집니다.
- 서버가 꺼져 있거나 오류가 나도 훅은 조용히 끝납니다(출력 없음, 종료 코드 0, 최대 2초). Claude Code 를 방해하지 않습니다.
- 알림에는 작업 요약(마지막 응답의 첫 줄)이 들어가므로 밖으로 열지 않습니다: 서버는 `127.0.0.1` 에만 바인드하고, 웹소켓은 localhost 출처만 허용하고, 브라우저가 보내는 메시지(SEND)와 `/topic/toast` 외의 구독은 모두 버립니다(알림 위조 방지). POST 는 `application/json` 만 받고, `Host` 가 localhost 계열이 아니면 403 입니다(악성 웹페이지가 도메인을 127.0.0.1 로 연결해 가짜 알림을 넣는 DNS 리바인딩 방어).

## 인프라 컨테이너

```bash
docker/manage.sh up mariadb         # 설치(이미지 받기) + 실행
docker/manage.sh down mariadb       # 중지 (데이터 유지)
docker/manage.sh destroy mariadb    # 데이터까지 삭제
docker/manage.sh list               # 사용 가능한 서비스 목록
```

- 새 서비스는 `docker/<이름>/docker-compose.yml` 만 만들면 `manage.sh` 로 바로 다룰 수 있습니다. 비밀번호 같은 값은 루트 `.env` 에 추가합니다.
- DB를 만들 때는 항상 `schema.sql` 을 함께 둡니다. (mariadb: `docker/mariadb/initdb/01-schema.sql`, 최초 기동 시 자동 적용)
- podman 에뮬레이션 환경에서는 `manage.sh` 가 사용자용 소켓(`/run/user/$(id -u)/podman/podman.sock`)을 자동으로 씁니다.

## 규칙

- 주제 하나당 `docs/<주제>.md` 에 핵심 개념, 실습 과정, 막혔던 점을 기록합니다.
- 비밀번호·회사 내부 코드·민감한 정보는 올리지 않습니다. 값은 `.env` 에만 둡니다.
- 새 화면은 대용량 적재 페이지 포맷을 따릅니다 (`CLAUDE.md` 참고).
