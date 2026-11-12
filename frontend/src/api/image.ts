import type { ImageBenchParams, ImageBenchResult, ImageStatus, ReplaceResult, ReplaceStrategy } from '../types'

const BASE = '/api/image'

async function parse<T>(res: Response): Promise<T> {
  const body = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(body.error ?? `HTTP ${res.status}`)
  return body as T
}

export const fetchImageStatus = () => fetch(`${BASE}/status`).then((r) => parse<ImageStatus>(r))

export const seedImages = (count: number, force = false, source: 'draw' | 'web' = 'draw') =>
  fetch(`${BASE}/seed?count=${count}&force=${force}&source=${source}`, { method: 'POST' }).then((r) =>
    parse<{ made: number; total: number; ms: number }>(r),
  )

export const runBench = (params: ImageBenchParams) =>
  fetch(`${BASE}/bench`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(params),
  }).then((r) => parse<ImageBenchResult>(r))

export const clearImageCache = () => fetch(`${BASE}/cache/clear`, { method: 'POST' }).then((r) => parse<{ deleted: number }>(r))

export const runReplace = (strategy: ReplaceStrategy, items: number, ttlSec: number) =>
  fetch(`${BASE}/replace-test?strategy=${strategy}&items=${items}&ttlSec=${ttlSec}`, { method: 'POST' }).then((r) => parse<ReplaceResult>(r))
