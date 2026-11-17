# Note 프로젝트 규칙

## 화면 포맷 (새 테스트 페이지를 만들 때 항상 따른다)
기준 구현: `frontend/src/views/BulkInsertView.vue` (대용량 적재).

- **셸**: 웹 제목 `Note`, 좌측 메뉴(LNB). 메뉴는 실제로 있는 항목만 둔다(`src/menu.ts`). 페이지 제목은 `router.ts`가 `"{페이지명} · Note"`로 설정한다.
- **페이지 구성**: 상단 `<h1>` + 한 줄 설명 → 카드 여러 개 → 오른쪽 `PageNav`(제목은 LNB의 페이지명, 항목은 카드 id/라벨, 스크롤 위치 표시).
- **카드**: 항상 `Card` 컴포넌트. 헤더는 아코디언, 기본 열림. 실행 버튼과 옵션(배치 크기, 스레드, 체크박스)은 카드 푸터(`footer` 슬롯)에 둔다.
- **실행 중**: 카드에 스피너 오버레이로 표시하고 최근 웹소켓 메시지를 보여 준다. 진행률 막대는 쓰지 않는다.
- **설명 문구**: 서술형 문장 대신 불릿 목록(`.legend`).
- **코드 예제**: 방식 선택은 라디오 버튼, 오른쪽에 `CodeBlock`(highlight.js)으로 언어별(XML/Java/SQL) 블록을 나눠 보여 준다. 영역은 고정 높이에 내부 스크롤.
- **결과 표**: 비교 기준 컬럼(총 소요)은 굵게, 최단은 파란색 `최단`, 최장은 빨간색 `최장` 표시.
- **실시간 로그**: 서버는 STOMP(`/ws-juso`, 구독 `/topic/juso`)로 보내고, 화면 우측 하단 `WsPanel` 아이콘에서 확인한다.
- **입력**: 파일 선택, 건수 제한 같은 입력은 두지 않는다. 데이터는 서버가 가진 파일을 쓴다.
- **스타일**: 색은 `style.css`의 CSS 토큰(라이트/다크)만 쓴다.

## 환경
- 포트는 모두 9000번대 (MariaDB 9000, Redis 9001, Vite 9201, gateway 9203, juso-service 9204, image-service 9205, catch-service 9206). 8000번대는 다른 프로젝트가 쓴다.
- 컴퓨터마다 다른 경로는 코드나 문서에 적지 않고 변수로 둔다. Ubuntu와 macOS 둘 다 스크립트(`scripts/`, `docker/manage.sh`)가 처리한다.
  - JDK 25: `.env`의 `JAVA_HOME`. 비워 두면 `scripts/lib/platform.sh`가 OS별로 자동 탐색한다(macOS는 `java_home`, Linux는 `~/.jdks`·`/usr/lib/jvm`).
  - docker: Ubuntu는 podman이라 `manage.sh`가 사용자 소켓(`/run/user/$(id -u)/podman/podman.sock`)을 자동으로 쓴다. macOS는 colima(`colima start`).
- DB 작업을 하면 `schema.sql`도 함께 만든다.
- 커밋/푸시는 사용자가 요청할 때만 한다.
- 커밋 메시지는 `feat:` / `fix:` / `docs:` / `chore:` 중 하나로 시작한다 (예: `feat: 캐치마인드 화면 추가`).
  - `feat`: 새 기능·화면 추가, `fix`: 버그 수정, `docs`: 스터디 노트(`docs/`)·README 작성과 수정, `chore`: 설정·빌드·스크립트·의존성·코드 정리 같은 그 외.
  - 종류가 섞인 변경은 종류별로 나눠서 커밋한다.
  - `Co-Authored-By` 줄은 넣지 않는다.

## 프로젝트 셋팅 ("프로젝트 구성 셋팅해줘")
처음 받은 컴퓨터에서 이 요청을 받으면 아래 순서로 진행한다. 각 단계는 이미 되어 있으면 건너뛴다.
1. **환경 확인**: OS(`uname -s`), JDK 25, Node 18+, docker. 없으면 설치 방법(macOS는 `brew install colima docker docker-compose` 후 `colima start`)을 말하고 설치 전에 사용자에게 확인한다.
2. **`.env`**: 비밀번호는 **절대 임의로 정하지 않는다.** 사용자에게 `MARIADB_ROOT_PASSWORD`, `MARIADB_PASSWORD`, `REDIS_PASSWORD`를 직접 정할지, 랜덤으로 만들지 묻는다.
   - 직접 입력: 사용자가 `! scripts/init-env.sh`를 실행하게 한다. 비밀번호가 대화에 남지 않는다.
   - 랜덤: 사용자가 원한다고 답했을 때만 `scripts/init-env.sh --random`을 실행하고, 값은 `.env`에 있다고 알려 준다.
   - 비밀번호는 영문, 숫자, `_ . @ % + -` 만 쓴다. 이미 `.env`가 있으면 건드리지 않는다.
3. **인프라**: `docker/manage.sh up mariadb` (이미지 캐싱을 쓰면 `up redis`도). healthy가 되는지 확인한다.
4. **데이터**: `generate-data/result/202610_juso.txt`(약 964MB)가 없으면, 내려받는 용량을 알리고 확인을 받은 뒤 `python3 generate-data/generate_juso.py`를 실행한다.
5. **빌드 확인**: `scripts/run-backend.sh`로 서비스가 뜨는지, 프론트는 `scripts/run-frontend.sh`(최초 `npm install`)로 확인한다.
6. 결과(된 것, 건너뛴 것, 사용자가 해야 할 것)를 정리해서 알린다. 커밋은 요청이 있을 때만 한다.
