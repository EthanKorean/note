import type { LockParams, LockResult, LockStatus } from '../lockTypes'

const BASE = '/api/lock'

async function parse<T>(res: Response): Promise<T> {
  const body = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(body.error ?? `HTTP ${res.status}`)
  return body as T
}

export const fetchLockStatus = () => fetch(`${BASE}/status`).then((r) => parse<LockStatus>(r))

/** 실험 하나를 실행한다. 끝날 때까지 응답이 오지 않는다(몇 초). */
export const runLock = (params: LockParams) =>
  fetch(`${BASE}/run`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(params),
  }).then((r) => parse<LockResult>(r))
