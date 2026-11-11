export type Engine = 'innodb'

export interface LoadMethod {
  value: string
  label: string
  hint: string
}

/** 서버에 전달하는 적재 옵션 (백엔드 LoadOptions 와 대응) */
export interface LoadParams {
  engine: Engine
  batchSize: number
  threads: number
  deferIndex: boolean
  relaxChecks: boolean
  truncate: boolean
}

/** 백엔드 LoadResult 응답 (시간은 모두 ms) */
export interface LoadResult {
  method: string
  engine: Engine
  table: string
  fileLines: number
  fileBytes: number
  verifiedCount: number
  prepareMs: number
  loadMs: number
  indexMs: number
  totalMs: number
  rowsPerSec: number
  rowsPerSecWithIndex: number
  mbPerSec: number
  options: LoadParams
}

/** 서버에 저장된 범용 테스트 결과 1건 (test_result 테이블) */
export interface SavedResult<T = unknown> {
  id: number
  testType: string
  name: string
  variant: string | null
  status: 'success' | 'fail'
  executedAt: string
  elapsedMs: number
  result: T
}

/** 화면에 보여 주는 적재 기록: 서버 결과 + 완료 시각 */
export interface RunRecord extends LoadResult {
  id: string
  finishedAt: string
}

export interface TableInfo {
  engineKey: Engine
  table: string
  engine: string
  count: number
  countMs: number
  dataMb: number
  indexMb: number
}
