# 대용량 INSERT 노하우와 적재 방식 비교

도로명주소 2026-10 고시분(`202610_juso.txt`, 6,426,355행, 약 964MB)을 MariaDB에 넣는 방법을 비교하기 위한 노트.
구현은 `backend/juso-service`, 화면은 `frontend`(적재 실행 / 실행 결과 / 테이블 상태).

## 1. 비교하는 적재 방식

API: `POST /api/juso/load/{method}` — 서버가 가진 데이터 파일(`juso.data-file`)을 적재한다. 업로드와 건수 제한은 없다.

| method | 방식 | 설명 |
|---|---|---|
| `loaddata-local` | `LOAD DATA LOCAL INFILE` | 앱이 파일을 읽어 DB로 스트리밍 |
| `parallel` | JDBC 멀티 로우 INSERT + 워커 N개 | 읽기 1 스레드 + 쓰기 N 스레드(각자 커넥션), 한 문장에 여러 행(바인드 변수 65,535개 제한 → 2,849행 이하) |
| `mybatis-batch` | MyBatis `ExecutorType.BATCH` | 행마다 매퍼 호출, 커밋 시 배치 전송 (단일 스레드) |
| `mybatis-parallel-batch` | MyBatis BATCH + 워커 N개 | 워커마다 트랜잭션(커넥션) |
| `mybatis-parallel-foreach` | MyBatis `<foreach>` 멀티 로우 + 워커 N개 | 행을 Map으로 넘김 |
| `jpa-parallel` | Spring Data JPA `saveAll` + 워커 N개 | PK를 직접 지정하면 행마다 SELECT가 나간다. InnoDB만 |

쿼리 파라미터: `engine`, `batchSize`, `threads`, `deferIndex`, `relaxChecks`, `truncate`.
응답에는 건수, 행 수 세기/적재/인덱스/전체 시간, 건/초, MB/초, 적재 후 `COUNT(*)` 검증값이 들어 있다.
화면(대용량 적재)에서는 방식을 체크해 여러 개를 순서대로 실행할 수 있고, 진행 메시지는 STOMP(`/ws-juso`, `/topic/juso`)로 받는다.

```bash
curl -X POST 'http://localhost:9203/api/juso/load/parallel?engine=innodb&threads=4&batchSize=2000&deferIndex=true'
```

한 번에 하나만 실행된다(실행 중이면 409). 끝날 때까지 응답이 오지 않는다.

## 1-1. 테스트 결과 저장 (범용)

테스트를 실행할 때마다 `test_result` 테이블에 1행이 쌓인다. 현재는 적재(`load`)를 저장하고, 이후 mqtt/kafka 같은 다른 테스트도 같은 방식으로 쓸 수 있게 만들었다.

| 컬럼 | 의미 |
|---|---|
| `test_type` | 테스트 종류 (`load`, …) |
| `name` | 무엇을 테스트했나 (적재 방식 이름 등) |
| `variant` | 조건/구분값 (엔진 등), 없으면 NULL |
| `status` | `success` \| `fail` — 실패도 기록(오류 메시지는 `result`에). 입력 오류(400)는 기록하지 않음 |
| `executed_at` | 테스트 완료 시각 |
| `elapsed_ms` | 소요시간. `load`는 서버 처리 전체(행 수 세기~검증) |
| `result` | 결과 JSON (TEXT) — 형식은 테스트마다 다름 |

API: `GET /api/test-results?type=load&status=success&name=batch&limit=50`, `DELETE /api/test-results?type=load`.
새 종류의 테스트를 만들 때는 `TestResultRepository.saveQuietly(testType, name, variant, status, elapsedMs, result)` 한 줄만 호출하면 된다 (코드: `backend/juso-service/.../testresult`).
`test_result`는 스키마를 다시 적용해도 지워지지 않는다(`CREATE TABLE IF NOT EXISTS`).

## 2. 노하우 체크리스트

1. **인덱스는 나중에** — 보조 인덱스를 지우고 적재한 뒤 다시 만든다(`deferIndex`). 행마다 인덱스를 갱신하는 비용이 사라지지만 재생성 시간이 따로 든다. 이전 측정에서는 이 테이블(보조 인덱스 2개)에서 총 소요에 이득이 없었고, 최종 비교는 인덱스를 둔 채로 했다.
2. **검사 끄기** — 세션에서 `unique_checks=0`, `foreign_key_checks=0` (`relaxChecks`). 데이터가 이미 정합하다는 확신이 있을 때만. 적재 후 반드시 원복(코드의 `JusoDb`가 반납 전에 복구).
3. **트랜잭션 단위** — autocommit을 끄고 N건마다 커밋. 한 건씩 커밋하면 느리고, 전부 한 트랜잭션이면 undo/로그가 커진다.
4. **PK 순서 삽입** — InnoDB는 PK(클러스터드 인덱스) 순서대로 넣을 때 페이지 분할이 적다. 병렬 적재는 순서가 섞이므로 이 이점이 줄어든다.
5. **파일은 스트리밍으로** — 전체를 메모리에 올리지 않고 청크 단위로 읽는다.
6. **LOAD DATA의 함정** — 빈 값은 `NULLIF(@c,'')`로 NULL 처리(다른 방식과 결과를 맞추기 위해), 원본 행 끝의 여분 `|`는 `@dummy`로 받는다, 문자셋 지정(아래 3번).
7. **JPA는 대량 적재용이 아니다** — PK를 직접 지정하는 엔티티의 `saveAll`은 `merge` → 행마다 SELECT. 대량 적재는 JDBC/LOAD DATA로 우회하고, JPA는 도메인 CRUD와 조회에 쓴다.
8. **서버 설정** — `innodb_flush_log_at_trx_commit=2`, `innodb_buffer_pool_size`, `innodb_log_file_size` 등. (이 프로젝트에서는 측정하지 않음 — 직접 `SET GLOBAL`로 바꿔 가며 비교해 볼 항목)
9. **검증은 필수** — 적재 후 `COUNT(*)`와 파일 행 수 비교. 결과 표에서 불일치하면 경고 태그가 뜬다.
10. **운영 패턴: 스테이징 + 교체** — 새 테이블에 적재하고 `RENAME TABLE`로 한 번에 교체하면 읽기 서비스가 중단되지 않는다. 월 1회 갱신되는 주소 데이터에 잘 맞는다. (미구현)

## 3. 문자셋 메모

- 원본은 **CP949**. Java 쪽은 `MS949`로 읽는다.
- **MariaDB에는 `cp949` 문자셋이 없고 `euckr`만 있다.** `LOAD DATA … CHARACTER SET euckr`를 쓴다.
- 202610 전체 6,426,355행 중 **175행에 확장 한글(UHC: 똠, 샾, 쁄 등)이 있다.** 이 MariaDB 11.4 컨테이너의 `euckr`는 이 글자들도 Java `MS949`와 동일하게 읽었다(코드 리뷰 중 전수 대조). 다른 MariaDB 버전이나 기준월에서는 달라질 수 있으니 UTF-8로 변환해 올리거나 JDBC 방식을 쓴다.
- LOAD DATA(euckr)와 batch(MS949)로 넣은 결과가 컬럼 값 기준 **동일**함을 2만 행으로 확인했고, 확장 한글 175행은 별도로 대조했다.

## 4. 엔진: Aria는 제외

읽기 중심 데이터라 Aria(MyISAM 대체)를 후보로 두고 같은 스키마로 한 번 비교했다. 642만 건 전체에서 모든 방식이 InnoDB보다 3.5~4.3배 느렸고(총 6분 47초~8분 21초 대 InnoDB 최선 1분 57초), 트랜잭션·롤백이 없어 실무 이점도 적어 테이블과 옵션을 제거했다. 원인은 확인하지 못했다(`aria_pagecache_buffer_size` 등 서버 설정이 의심됨).

## 5. 전체 데이터 결과

642만 6,355행(964MB), 내 PC의 MariaDB 11.4 컨테이너(InnoDB), **보조 인덱스 `idx_road`·`idx_zip`을 둔 상태로** 적재했다. 입력 속도가 관심사라 "인덱스를 나중에 만들기" 비교는 하지 않았다(`deferIndex` 옵션은 코드에 남아 있고 화면에서는 뺐다).
병렬 방식은 4스레드. 방식·배치 크기 조합마다 **1회**씩 총 20회 측정했고, 모두 적재 후 `COUNT(*)`가 파일 행 수와 일치했다.
총 소요 = 행 수 세기 ~ 건수 검증. 같은 PC에서 화면 확인 등을 하던 구간이 있어 몇 초 차이는 오차로 본다.

| 순위 | 방식 | 배치 크기 | 총 소요 | 속도(건/초) |
|---|---|---|---|---|
| 1 | `parallel` | 2,000 / 1,000 | 55초 / 1분 03초 | 128,935 / 110,994 |
| 2 | `mybatis-parallel-batch` | 10,000 / 30,000 / 2,000 / 1,000 / 5,000 | 1분 00초 / 1분 02초 / 1분 05초 / 1분 07초 / 1분 09초 | 101,357 ~ 114,185 |
| 3 | `loaddata-local` | – | 1분 34초 | 70,343 |
| 4 | `mybatis-parallel-foreach` | 1,000 / 2,000 | 2분 04초 / 2분 08초 | 54,030 / 52,061 |
| 5 | `mybatis-batch` (단일) | 30,000 / 10,000 / 5,000 / 2,000 / 1,000 | 2분 57초 / 2분 57초 / 3분 04초 / 3분 06초 / 3분 13초 | 34,201 ~ 37,365 |
| 6 | `jpa-parallel` | 2,000 / 30,000 / 10,000 / 1,000 / 5,000 | 4분 21초 / 4분 23초 / 4분 25초 / 4분 28초 / 4분 30초 | 24,164 ~ 25,105 |

- `parallel`, `mybatis-parallel-foreach`는 한 문장에 여러 행을 넣어 바인드 변수 제한으로 배치 크기가 2,849 이하다. 그래서 1,000/2,000만 측정했다.
- **결과를 가른 것은 방식이다.** 한 방식 안에서 배치 크기를 바꿔도 총 소요 차이는 최대 약 15%(`mybatis-parallel-batch` 1분 00초~1분 09초), 방식 사이 차이는 최대 5배 이상이다.
- **병렬 쓰기가 가장 큰 효과**: `mybatis-batch`가 단일 3분 안팎에서 병렬 1분 안팎으로 줄었다. 이전 단일 측정에서 `jpa`는 14분 15초, `mybatis-foreach`는 6분 33초였고 병렬로 각각 4분 반, 2분 남짓이 됐다(그 값은 화면 초기화로 지워져 이 대화/문서 기록에만 남음).
- **직접 JDBC 병렬(`parallel`)이 가장 빠르지만** MyBatis 병렬 batch와 5초(약 9%) 차이다. 프레임워크 없이 JDBC만 쓰는 일은 실무에서 드물어 "가장 빠른 기준선" 정도로 본다.
- **LOAD DATA(1분 34초)는 병렬 방식보다 느렸다.** 단일 연결이라는 점이 원인으로 보이지만 따로 검증하지는 않았다.
- **JPA는 크기와 무관하게 4분 반**: `hibernate.jdbc.batch_size=1000`에 묶여 우리가 청크를 키워도 실제 전송 단위가 같고, PK를 직접 지정해 merge(행마다 SELECT)가 된다.

## 6. 장단점과 트랜잭션

화면의 "방식별 장단점 · 느낀점" 카드와 같은 내용이다. 수치는 위 5장, 나머지는 일반적으로 알려진 특성과 구현하며 겪은 점이다.

| 방식 | 장점 | 단점 |
|---|---|---|
| `loaddata-local` | 문장 1개라 코드가 가장 짧다. 가공 없는 파일을 넣을 때 단순하다. 파일이 DB 서버에 없어도 된다 | 병렬 방식보다 느렸다. 행 단위 가공·검증이 어렵고 `NULLIF`, `euckr` 같은 처리를 SQL에 적어야 한다. 중복 행을 조용히 건너뛸 수 있다 |
| `parallel` | 가장 빠르다(55초). 문장과 커넥션을 직접 통제한다 | 스레드·큐·커넥션을 직접 짠다. SQL 문자열 조립, 문장당 2,849행 상한. 순서가 섞인다 |
| `mybatis-batch` | 코드가 단순하고 SQL이 XML에 분리된다. 단일 스레드라 순서가 보존된다 | 병렬 batch의 약 3배. 컬럼 23개를 XML에 직접 나열한다 |
| `mybatis-parallel-batch` | 직접 JDBC에 가까운 속도. 배치 크기 영향이 작고 바인드 변수 제한이 없다 | 병렬 골격을 직접 만든다. 스레드 수가 커넥션 풀(12)에 묶인다 |
| `mybatis-parallel-foreach` | 문장 하나에 여러 행이라 왕복이 적다 | Map 변환과 SQL 조립 비용으로 병렬 batch의 약 2배. 2,849행 상한 |
| `jpa-parallel` | 엔티티·리포지토리를 그대로 쓰고 도메인 검증을 붙이기 쉽다 | 가장 느리다. 행마다 SELECT(merge), 영속성 컨텍스트 메모리, 검사 끄기 불가 |

**트랜잭션 관리**

| 방식 | 트랜잭션 단위 | 중간에 실패하면 |
|---|---|---|
| `loaddata-local` | 오토커밋 상태에서 문장 1개 = 1개 (InnoDB 기준) | 642만 행이 한 번에 커밋되거나 취소. 단 LOCAL은 중복 키 같은 오류가 경고로 바뀌어 건너뛰므로 COUNT 검증 필수. 커밋 단위를 정할 수 없고 undo 로그가 커진다 |
| `parallel` | 워커마다 커넥션, 오토커밋 끄고 청크마다 직접 `commit()` | 이미 커밋된 청크는 남는다(전체 롤백 없음) |
| `mybatis-batch` | `TransactionTemplate`으로 청크마다 | 그 청크만 롤백, 앞 청크는 남는다. 커밋 직전에 배치 전송 |
| `mybatis-parallel-batch` | 워커 스레드마다 `TransactionTemplate`(=자기 커넥션)으로 청크마다 | 그 청크만 롤백, 나머지 워커는 중단 |
| `mybatis-parallel-foreach` | 워커마다 청크(문장) 하나를 한 트랜잭션으로 | 그 청크만 롤백 |
| `jpa-parallel` | 워커마다 청크(`saveAll`)를 한 트랜잭션, 영속성 컨텍스트는 트랜잭션마다 새로 | 그 트랜잭션만 롤백, 하이버네이트가 `batch_size`(1,000)개씩 flush |

- 어떤 방식이든 청크 단위 커밋이면 **전체를 원자적으로 되돌릴 수는 없다.** 이 프로젝트는 실패를 `test_result`에 기록하고 다음 실행에서 테이블을 비워 다시 넣는 방식으로 처리한다. 실서비스라면 노하우 10번(스테이징 테이블 + `RENAME TABLE`)이 맞다(미구현).
- LOAD DATA의 롤백 동작은 이 프로젝트에서 실패 상황을 만들어 확인하지 않았다.

## 7. 선택 기준과 제외한 방식

- **기본 선택은 MyBatis 병렬 batch**: 속도는 직접 JDBC에 가깝고 코드 관리 부담이 작다.
- 속도가 최우선이고 직접 관리할 수 있으면 직접 JDBC 병렬(`parallel`).
- 가공 없는 파일을 한 번 넣는 일이면 LOAD DATA.
- JPA는 대량 적재에 쓰지 않는다.

한때 비교했지만 실무에서 쓸 일이 적다고 판단해 코드와 화면에서 뺀 방식(참고, 이전 측정 1회, 단위 총 소요): `loaddata-server` 1.7분(같은 머신에서 LOCAL과 같고 FILE 권한 필요), `batch`(직접 JDBC 배치) 1.7분, `multirow`(단일 스레드 멀티 로우) 2.5분, `mybatis-foreach`(단일) 6.6분, `jpa`(단일) 14.3분. 이 값은 인덱스를 나중에 만든 조건(JPA는 즉시)이라 5장과 직접 비교하지 않는다.

## 8. 남은 항목 (미검증)

- 서버 설정(`innodb_flush_log_at_trx_commit=2`, `innodb_buffer_pool_size`, `innodb_log_file_size`)이 적재 시간에 미치는 영향.
- MariaDB 드라이버의 `useBulkStmts` 옵션이 JDBC batch 속도에 미치는 영향.
- 스레드 수(2/4/8)에 따른 변화. 현재는 4로 고정했다.
- 조회 속도: `road_nm`에는 인덱스가 없어 풀 데이터에서는 느릴 수 있다. 조회 화면은 제거했다.
- 스테이징 테이블 + `RENAME TABLE` 교체 패턴(위 노하우 10번)은 미구현.
