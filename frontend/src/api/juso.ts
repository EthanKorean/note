import type { LoadParams, LoadResult, SavedResult, TableInfo } from '../types'

const BASE = '/api/juso'

async function parse<T>(res: Response): Promise<T> {
  const body = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(body.error ?? `HTTP ${res.status}`)
  return body as T
}

/** 저장된 테스트 결과 (범용 API). 최근 순. */
export const fetchResults = <T>(type: string, status = 'success', limit = 100) =>
  fetch(`/api/test-results?type=${type}&status=${status}&limit=${limit}`).then((r) => parse<SavedResult<T>[]>(r))

export const clearResults = (type: string) =>
  fetch(`/api/test-results?type=${type}`, { method: 'DELETE' }).then((r) => parse<{ deleted: number }>(r))

export const fetchTables = () => fetch(`${BASE}/tables`).then((r) => parse<TableInfo[]>(r))

export const truncateTable = (engine: string) =>
  fetch(`${BASE}/truncate?engine=${engine}`, { method: 'POST' }).then((r) => parse<{ truncated: string }>(r))

/** 서버에 있는 데이터 파일을 지정한 방식으로 적재한다(업로드 없음). 끝날 때까지 응답이 오지 않는다. */
export function startLoad(method: string, params: LoadParams): Promise<LoadResult> {
  const q = new URLSearchParams({
    engine: params.engine,
    batchSize: String(params.batchSize),
    threads: String(params.threads),
    deferIndex: String(params.deferIndex),
    relaxChecks: String(params.relaxChecks),
    truncate: String(params.truncate),
  })
  return fetch(`${BASE}/load/${method}?${q}`, { method: 'POST' }).then((r) => parse<LoadResult>(r))
}

export const fetchStatus = () => fetch(`${BASE}/status`).then((r) => parse<{ running: boolean }>(r))
