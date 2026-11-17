/** rag-service(/api/rag) 응답 형식. 백엔드 RagController, EvalRunner 의 record 와 대응한다. */
export interface RagStatus {
  modelReady: boolean
  modelBytes: number
  modelDir: string
  docsDir: string
  docs: { name: string; chars: number }[]
  questions: number
  /** 정답 문구를 문서에서 못 찾은 질문(문서가 바뀌어 질문이 낡은 경우) */
  unmatched: string[]
  error: string | null
  running: boolean
  current: string | null
}

export interface RagMiss {
  question: string
  top1: string
}

export interface RagRow {
  retriever: string
  hit1: number
  hit3: number
  hit5: number
  mrr: number
  avgMs: number
  p95Ms: number
  misses: RagMiss[]
}

export interface RagEvalParams {
  chunker: string
  size: number
  overlap: number
  retrievers: string[]
}

export interface RagEvalResult {
  chunker: string
  size: number
  overlap: number
  chunks: number
  avgChars: number
  questions: number
  unreachable: number
  /** 임베딩 입력 한도(512 토큰)를 넘어 뒤가 잘린 조각 수. 이 조각은 벡터 검색이 앞부분만 본다 */
  truncated: number
  rows: RagRow[]
  finishedAt: string
  /** 서버가 test_result 에 저장했는지. false 면 새로고침하면 사라진다 */
  saved: boolean
}

export interface RagRun extends RagEvalResult {
  id: string
}

export interface RagHit {
  rank: number
  score: number
  source: string
  heading: string
  text: string
}

export interface RagSearchResult {
  retriever: string
  chunker: string
  chunks: number
  ms: number
  hits: RagHit[]
}

export interface RagSearchParams {
  q: string
  retriever: string
  chunker: string
  size: number
  overlap: number
  topK: number
}
