-- 도로명주소 한글 전체분 (rnaddrkor_*.txt, 기준월 202610) 적재용 스키마
--
-- 컨테이너를 처음 만들 때 /docker-entrypoint-initdb.d 에서 자동 실행된다.
-- (데이터 볼륨이 이미 있으면 실행되지 않음 → 다시 적용하려면 `./manage.sh destroy mariadb` 후 up,
--  또는 `docker exec -i note-mariadb mariadb -uroot -p... < 01-schema.sql`)
--
--   rnaddr_kor  InnoDB — 기본 엔진. 트랜잭션, 크래시 복구, PK 클러스터링
--   (Aria 비교용 테이블은 측정에서 InnoDB보다 크게 느려 제외했다)
--
-- 주의: 컬럼명/의미는 데이터 샘플과 일반적인 도로명주소 한글 전체분 구조로 추정한 것이다.
--       공식 스키마(https://business.juso.go.kr/jst/jstAddressDownload)와 대조 후 확정할 것.
--       (202610 전체 6,426,355행으로 PK 중복 0건, 컬럼 최대 길이가 정의 범위 안임은 확인함)
--
-- 원본 파일 특성: 구분자 '|', 인코딩 CP949, 행 끝에 '|'가 하나 더 붙음, 빈 값은 빈 문자열(→ NULL로 적재).
--
-- 보조 인덱스 이름(idx_road, idx_zip)은 backend juso-service 의 JusoTables 와 맞춰야 한다.

CREATE DATABASE IF NOT EXISTS juso DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE juso;

DROP TABLE IF EXISTS rnaddr_kor_aria;   -- 이전 버전에서 만든 테이블 정리
DROP TABLE IF EXISTS rnaddr_kor;

CREATE TABLE rnaddr_kor (
    mng_no          CHAR(26)     NOT NULL COMMENT '도로명주소관리번호',
    bjd_code        CHAR(10)     NOT NULL COMMENT '법정동코드',
    sido_nm         VARCHAR(40)  NOT NULL COMMENT '시도명',
    sigungu_nm      VARCHAR(40)  NULL     COMMENT '시군구명',
    emd_nm          VARCHAR(40)  NULL     COMMENT '법정읍면동명',
    ri_nm           VARCHAR(40)  NULL     COMMENT '법정리명',
    san_yn          CHAR(1)      NULL     COMMENT '산여부(0:대지,1:산)',
    jibun_main      INT          NULL     COMMENT '지번본번',
    jibun_sub       INT          NULL     COMMENT '지번부번',
    road_code       CHAR(12)     NOT NULL COMMENT '도로명코드(시군구코드5+도로명번호7)',
    road_nm         VARCHAR(80)  NULL     COMMENT '도로명',
    basement_yn     CHAR(1)      NULL     COMMENT '지하여부(0:지상,1:지하,2:공중)',
    bld_main        INT          NULL     COMMENT '건물본번',
    bld_sub         INT          NULL     COMMENT '건물부번',
    hjd_code        CHAR(10)     NULL     COMMENT '행정동코드',
    hjd_nm          VARCHAR(40)  NULL     COMMENT '행정동명',
    zip_code        CHAR(5)      NULL     COMMENT '기초구역번호(우편번호)',
    prev_road_addr  VARCHAR(200) NULL     COMMENT '이전도로명주소',
    effective_date  CHAR(8)      NULL     COMMENT '효력발생일(YYYYMMDD)',
    apt_type        CHAR(1)      NULL     COMMENT '공동주택구분(0:비공동,1:공동)',
    move_reason     CHAR(2)      NULL     COMMENT '이동사유코드',
    bld_nm          VARCHAR(100) NULL     COMMENT '건축물대장건물명',
    bld_nm_sigungu  VARCHAR(100) NULL     COMMENT '시군구용건물명',
    PRIMARY KEY (mng_no),
    KEY idx_road (road_code, bld_main, bld_sub),
    KEY idx_zip (zip_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='도로명주소 한글 전체분 (InnoDB)';

-- 범용 테스트 결과 저장소 (테스트 1회 = 1행). 적재/조회뿐 아니라 이후 어떤 테스트(mqtt, kafka …)든 쓴다.
-- 결과 형식이 테스트마다 달라 result는 JSON 문자열(TEXT)로 두고, 거르는 데 쓰는 값만 컬럼으로 뺐다.
-- 위 데이터 테이블과 달리 DROP 하지 않는다(스키마를 다시 적용해도 기록 유지).
CREATE TABLE IF NOT EXISTS test_result (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    test_type    VARCHAR(50)  NOT NULL COMMENT '테스트 종류 (예: load, search, mqtt-throughput)',
    name         VARCHAR(100) NOT NULL COMMENT '무엇을 테스트했나 (예: 적재 방식 이름)',
    variant      VARCHAR(100) NULL     COMMENT '조건/구분값 (예: 엔진 innodb)',
    status       VARCHAR(10)  NOT NULL DEFAULT 'success' COMMENT 'success | fail',
    executed_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '테스트 완료 시각',
    elapsed_ms   BIGINT       NOT NULL COMMENT '소요시간(ms). load는 서버 처리 전체(행 수 세기~검증)',
    result       TEXT         NOT NULL COMMENT '결과 JSON (실패 시 error 메시지)',
    PRIMARY KEY (id),
    KEY idx_type_executed (test_type, executed_at),
    KEY idx_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='테스트 결과';
