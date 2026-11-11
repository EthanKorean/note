/**
 * 전체 데이터 적재 결과 정리(고정 문구). 실행 결과 표는 서버에 저장된 실제 기록이고,
 * 이 파일은 그중 최종 측정 20회를 사람이 읽기 좋게 요약한 것이다. docs/bulk-insert.md 5~6장과 같은 내용이다.
 */
export interface SummaryRow {
  method: string
  label: string
  batch: string
  total: string
  speed: string
  note: string
}

export const summaryConditions = [
  '데이터: 도로명주소 2026-10 고시분 6,426,355행 (964MB)',
  'DB: MariaDB 11.4 컨테이너, InnoDB, 보조 인덱스 2개(idx_road, idx_zip)를 둔 상태로 적재',
  '병렬 방식은 4스레드. 방식·배치 크기 조합마다 1회 측정(총 20회), 모두 적재 후 건수가 파일 행 수와 일치',
  '총 소요는 행 수 세기부터 건수 검증까지. 같은 PC에서 화면 확인 등이 겹친 구간이 있어 몇 초 차이는 오차로 봐야 한다',
]

export const summaryRows: SummaryRow[] = [
  { method: 'parallel', label: '직접 JDBC 병렬 멀티 로우', batch: '1,000 ~ 2,000', total: '55초 ~ 1분 03초', speed: '11.1만 ~ 12.9만', note: '가장 빠름. 한 문장 행 수 상한 2,849' },
  { method: 'mybatis-parallel-batch', label: 'MyBatis 병렬 batch', batch: '1,000 ~ 30,000', total: '1분 00초 ~ 1분 09초', speed: '10.1만 ~ 11.4만', note: '배치 크기 영향 거의 없음' },
  { method: 'loaddata-local', label: 'LOAD DATA LOCAL', batch: '-', total: '1분 34초', speed: '7.0만', note: '문장 1개, 단일 연결' },
  { method: 'mybatis-parallel-foreach', label: 'MyBatis 병렬 foreach', batch: '1,000 ~ 2,000', total: '2분 04초 ~ 2분 08초', speed: '5.2만 ~ 5.4만', note: 'SQL 조립 비용이 큼' },
  { method: 'mybatis-batch', label: 'MyBatis batch (단일)', batch: '1,000 ~ 30,000', total: '2분 57초 ~ 3분 13초', speed: '3.4만 ~ 3.7만', note: '단일 스레드가 병목' },
  { method: 'jpa-parallel', label: 'JPA 병렬 saveAll', batch: '1,000 ~ 30,000', total: '4분 21초 ~ 4분 30초', speed: '2.4만 ~ 2.5만', note: '행마다 SELECT(merge), 크기 무관' },
]

export const summaryFindings = [
  '결과를 가른 것은 배치 크기가 아니라 방식이다. 방식마다 배치 크기를 바꿔도 총 소요 차이는 최대 약 15%였다(방식 간 차이는 최대 5배 이상)',
  '병렬로 쓰는 것이 가장 큰 효과였다. MyBatis batch는 단일 3분 안팎에서 병렬 1분 안팎으로 줄었다',
  '프레임워크 없이 JDBC로 직접 짠 병렬이 가장 빠르지만 MyBatis 병렬 batch와 5초(약 9%) 차이였다',
  'LOAD DATA(1분 34초)는 단일 연결이라 병렬 방식보다 느렸다. 코드가 가장 짧다는 점이 장점이다',
  'JPA는 병렬로 해도 가장 느렸고 배치 크기를 바꿔도 같았다(하이버네이트 batch_size 1,000에 묶임)',
]

export const summaryGuide = [
  '기본 선택은 MyBatis 병렬 batch: 속도는 직접 JDBC에 가깝고 코드 관리 부담이 작다',
  '속도가 최우선이고 직접 관리할 수 있으면 직접 JDBC 병렬(parallel)',
  '가공 없는 파일을 한 번 넣는 일이면 LOAD DATA: 가장 단순하지만 커밋 단위를 정할 수 없고 LOCAL은 오류가 경고로 바뀔 수 있다',
  'JPA는 대량 적재에 쓰지 않는다. 도메인 데이터 저장에만 쓴다',
]
