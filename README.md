# note

Vue + Spring Boot로 여러 주제를 공부하고, 작은 프로젝트를 직접 만들어 보는 **스터디용 프로젝트**입니다.
공부하고 기록해 두는 용도로, 배운 내용은 이 저장소에 코드와 문서로 쌓아 갑니다.

## 구조

```
note/
├── .env.example     환경변수 견본 (복사해서 .env 를 만든다. .env 는 커밋되지 않음)
├── backend/         Gradle 멀티 프로젝트 (MSA, Spring Boot 3.2 / Java 17)
│   ├── gateway/         Spring Cloud Gateway (9203) — 모든 요청의 진입점
│   ├── juso-service/    도로명주소 대용량 적재 API + STOMP 웹소켓 (9204)
│   └── image-service/   이미지 캐싱 실험 API (9205, Redis)
├── frontend/        Vue 3 + TypeScript + Pinia (Vite, 9201) — 좌측 메뉴 + 대용량 적재 화면
├── generate-data/   대용량 테스트 데이터 생성 (도로명주소 → result/202610_juso.txt)
├── docker/          인프라 컨테이너 모음 (mariadb, redis, 이후 mqtt/kafka 등) + manage.sh
├── scripts/         .env 만들기, 백엔드/프론트 실행 스크립트
└── docs/            스터디 노트 (주제별 정리)
```

## 기술 스택

| 영역 | 사용 기술 |
|---|---|
| Backend | Java 17, Spring Boot 3.2.4, Spring Cloud Gateway, JDBC / MyBatis / Spring Data JPA, STOMP(WebSocket), Spring Data Redis(Lettuce), MariaDB 11.4, Redis 7 |
| Frontend | Vue 3, TypeScript, Pinia, vue-router, highlight.js, Vite 5 |
| Build | Gradle (wrapper 포함), npm |

## 어떤 파일을 어디에 둬야 하나

저장소에 올라가지 않는 파일(비밀번호, 대용량 데이터)은 직접 만들어 아래 위치에 둡니다.

| 파일 | 위치 | 만드는 법 | 비고 |
|---|---|---|---|
| `.env` | 프로젝트 **루트** (`note/.env`) | `scripts/init-env.sh` 로 `.env.example` 을 복사한 뒤 비밀번호 채움 | 커밋 금지(`.gitignore`). DB 계정/비밀번호, JDK 위치, 데이터 파일 경로 |
| `202610_juso.txt` | `generate-data/result/` | `python3 generate-data/generate_juso.py` | 964MB, 커밋 금지. DB 컨테이너가 `/import` 로 같은 폴더를 읽는다 |
| MariaDB 데이터 | 컨테이너 볼륨 `mariadb-data` | `docker/manage.sh up mariadb` | 최초 기동 시 `docker/mariadb/initdb/01-schema.sql` 자동 적용 |
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
| `JAVA_HOME` | `java` 가 PATH 에 없을 때 JDK 17 위치 |

코드와 설정 파일(`application.yml`, `docker-compose.yml`)에는 비밀번호를 적지 않습니다. 비어 있으면 실행이 중단됩니다.

## 실행 방법

필요한 것: JDK 17, Node.js 18+, docker(또는 podman)

```bash
# 0) 처음 한 번: .env 만들고 비밀번호 채우기, 데이터 준비
scripts/init-env.sh                       # note/.env 생성 → 편집기로 비밀번호 채움
python3 generate-data/generate_juso.py    # generate-data/result/202610_juso.txt

# 1) DB (mariadb: localhost:9000, DB juso), 캐시(redis: localhost:9001)
docker/manage.sh up mariadb
docker/manage.sh up redis                 # 이미지 캐싱 화면에서만 필요

# 2) 백엔드 — 터미널을 나눠 각각 실행 (.env 를 읽어 환경변수로 넘겨 줌)
scripts/run-backend.sh juso               # http://localhost:9204
scripts/run-backend.sh image              # http://localhost:9205 (이미지 캐싱 화면에서만 필요)
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
- 수박 게임(Canvas + 직접 구현한 2D 물리) 만든 과정: [docs/suika-game.md](docs/suika-game.md)

## 인프라 컨테이너

```bash
docker/manage.sh up mariadb         # 설치(이미지 받기) + 실행
docker/manage.sh down mariadb       # 중지 (데이터 유지)
docker/manage.sh destroy mariadb    # 데이터까지 삭제
docker/manage.sh list               # 사용 가능한 서비스 목록
```

- 새 서비스는 `docker/<이름>/docker-compose.yml` 만 만들면 `manage.sh` 로 바로 다룰 수 있습니다. 비밀번호 같은 값은 루트 `.env` 에 추가합니다.
- DB를 만들 때는 항상 `schema.sql` 을 함께 둡니다. (mariadb: `docker/mariadb/initdb/01-schema.sql`, 최초 기동 시 자동 적용)
- podman 에뮬레이션 환경에서는 `manage.sh` 가 사용자용 소켓(`/run/user/<uid>/podman/podman.sock`)을 자동으로 씁니다.

## 규칙

- 주제 하나당 `docs/<주제>.md` 에 핵심 개념, 실습 과정, 막혔던 점을 기록합니다.
- 비밀번호·회사 내부 코드·민감한 정보는 올리지 않습니다. 값은 `.env` 에만 둡니다.
- 새 화면은 대용량 적재 페이지 포맷을 따릅니다 (`CLAUDE.md` 참고).
