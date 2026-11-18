/** lock-service(/api/lock) 응답 형식. 백엔드 LockController, LockLab 의 record 와 대응한다. */
export interface LockStatus {
  running: boolean
  current: string | null
  /** DB 에 연결하지 못했거나 실험 테이블이 없을 때의 메시지 */
  error: string | null
  experiments: string[]
}

export interface LockProduct {
  id: number
  initial: number
  sold: number
  finalStock: number
}

export interface LockParams {
  id: string
  buyers?: number
  stock?: number
  payMs?: number
}

export interface LockResult {
  id: string
  group: 'stock' | 'deadlock' | 'tx'
  title: string
  buyers: number
  threads: number
  payMs: number
  elapsedMs: number
  ok: number
  soldOut: number
  failed: number
  deadlocks: number
  timeouts: number
  retries: number
  /** DB 에 실제로 남은 주문 수(ok 와 같아야 한다) */
  orders: number
  products: LockProduct[]
  /** 재고보다 더 팔린 수량 */
  oversold: number
  /** 처음 재고 - 팔린 수량 = 남은 재고이고, 재고가 음수가 아니며, 주문 수가 맞으면 true */
  consistent: boolean
  firstError: string | null
  finishedAt: string
  /** 서버가 test_result 에 저장했는지. false 면 새로고침하면 사라진다 */
  saved: boolean
}

export interface LockRun extends LockResult {
  /** 화면에서 구분하려는 번호(서버 저장분은 s{id}, 이 화면에서 방금 돌린 것은 c{n}) */
  key: string
}
