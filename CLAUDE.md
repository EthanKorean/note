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
- 포트는 모두 9000번대 (MariaDB 9000, Redis 9001, Vite 9201, gateway 9203, juso-service 9204, image-service 9205). 8000번대는 다른 프로젝트가 쓴다.
- `java`는 PATH에 없다: `JAVA_HOME=/home/ethan/.jdks/corretto-17.0.12`. docker는 podman이라 `DOCKER_HOST=unix:///run/user/1004/podman/podman.sock`.
- DB 작업을 하면 `schema.sql`도 함께 만든다.
- 커밋/푸시는 사용자가 요청할 때만 한다.
