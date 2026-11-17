import type { RagEvalParams, RagEvalResult, RagSearchParams, RagSearchResult, RagStatus } from '../ragTypes'

const BASE = '/api/rag'

async function parse<T>(res: Response): Promise<T> {
  const body = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(body.error ?? `HTTP ${res.status}`)
  return body as T
}

export const fetchRagStatus = () => fetch(`${BASE}/status`).then((r) => parse<RagStatus>(r))

/** 모델을 내려받아 올리고 문서를 다시 읽는다. 처음에는 내려받느라 시간이 걸린다. */
export const prepareRag = () => fetch(`${BASE}/prepare`, { method: 'POST' }).then((r) => parse<RagStatus>(r))

export const runRagEval = (params: RagEvalParams) =>
  fetch(`${BASE}/eval`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(params),
  }).then((r) => parse<RagEvalResult>(r))

export const searchRag = (p: RagSearchParams) => {
  const qs = new URLSearchParams({
    q: p.q,
    retriever: p.retriever,
    chunker: p.chunker,
    size: String(p.size),
    overlap: String(p.overlap),
    topK: String(p.topK),
  })
  return fetch(`${BASE}/search?${qs}`).then((r) => parse<RagSearchResult>(r))
}
