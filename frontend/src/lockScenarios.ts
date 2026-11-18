/** 락 전략 시나리오(순서대로 따라 하는 실험)와 실험 이름. 실험 id 는 백엔드 Experiment 와 같다. */
export interface LockExperiment {
  id: string
  /** stock(재고 차감 방식) | deadlock(여러 상품을 잠그는 순서) | tx(트랜잭션 길이). 백엔드 Experiment.group() 과 같다 */
  group: 'stock' | 'deadlock' | 'tx'
  label: string
  /** 한 줄 설명(표의 방식 칸 아래) */
  how: string
}

export const experiments: LockExperiment[] = [
  { id: 'stock-none', group: 'stock', label: '락 없음', how: '재고를 읽고, 계산해서, 그 값을 쓴다' },
  { id: 'stock-atomic', group: 'stock', label: '원자적 UPDATE', how: 'SET stock = stock - 1 WHERE stock >= 1 한 문장' },
  { id: 'stock-pessimistic', group: 'stock', label: '비관적 락', how: 'SELECT … FOR UPDATE 로 읽을 때부터 잠금' },
  { id: 'stock-optimistic', group: 'stock', label: '낙관적 락', how: '버전을 확인하고 쓰기, 충돌하면 다시 시도' },
  { id: 'deadlock-cross', group: 'deadlock', label: '서로 다른 순서로 잠금', how: '주문마다 상품 1→2 또는 2→1 순서' },
  { id: 'deadlock-ordered', group: 'deadlock', label: '상품 번호 순서로 잠금', how: '모든 주문이 1→2 순서' },
  { id: 'deadlock-retry', group: 'deadlock', label: '데드락 나면 다시 시도', how: '순서는 그대로, 밀려나면 처음부터 최대 5번' },
  { id: 'tx-long', group: 'tx', label: '결제를 트랜잭션 안에서', how: '행을 잠근 채로 결제를 기다린다' },
  { id: 'tx-short', group: 'tx', label: '결제를 트랜잭션 밖에서', how: '결제는 동시에, 재고 차감만 짧게' },
]

export const experimentOf = (id: string) => experiments.find((e) => e.id === id)

/** 실험 그룹별 기본 조건. 백엔드(Experiment.defaultBuyers, LockLab)의 기본값과 같다. 시나리오는 이 조건으로 돌린 결과만 보여 준다. */
export const DEFAULTS = {
  buyers: { stock: 100, deadlock: 20, tx: 50 },
  stock: 10,
  payMs: 20,
} as const

/** 이 결과가 시나리오의 기본 조건으로 돌린 것인지(직접 실행에서 조건을 바꿔 돌린 결과와 구분한다). */
export function isDefaultRun(r: { id: string; buyers: number; payMs: number; products: { initial: number }[] }): boolean {
  const g = experimentOf(r.id)?.group
  if (!g || r.buyers !== DEFAULTS.buyers[g]) return false
  if (g === 'stock') return r.products[0]?.initial === DEFAULTS.stock
  if (g === 'tx') return r.payMs === DEFAULTS.payMs
  return true
}

export interface LockScenario {
  id: string
  no: number
  title: string
  why: string
  /** 보는 법 */
  look: string
  /** 내 PC 에서 쟀을 때의 값(직접 돌린 결과와 비교하는 용도) */
  reference: string
  /** 이 시나리오의 실행 버튼이 돌리는 실험 */
  run: string[]
  /** 결과 표에 보여 줄 실험(앞 시나리오의 결과와 비교하려고 run 보다 많을 수 있다) */
  show: string[]
  /** 실험 조건(화면에 한 줄로 보여 준다) */
  condition: string
}

export const scenarios: LockScenario[] = [
  {
    id: 'scn-none',
    no: 1,
    title: '락 없이 동시에 주문하면',
    why: '재고 10개인 한정판 운동화에 100명이 동시에 주문한다. 재고를 읽고(SELECT), 계산하고, 쓰는(UPDATE) 가장 흔한 코드다. 읽은 뒤 쓰기 전에 다른 주문이 재고를 바꿔도 모른다',
    look: '성공한 주문이 재고(10)보다 많으면 초과 판매다. "재고 검증"이 어긋남이면 팔린 수량과 남은 재고가 맞지 않는 것이다. 돌릴 때마다 숫자는 조금 다르다',
    reference: '100건 모두 성공, 초과 판매 90건, 남은 재고 7(팔린 수량으로 계산하면 -90이어야 한다)',
    run: ['stock-none'],
    show: ['stock-none'],
    condition: '재고 10 · 주문 100건 · 동시 50',
  },
  {
    id: 'scn-stock',
    no: 2,
    title: '재고 차감 3가지 방법',
    why: '같은 상황을 락으로 막는 방법이 세 가지 있다. 모두 10개만 팔리지만 비용과 동작이 다르다. 위 1번의 결과와 같이 비교한다',
    look: '세 방식 모두 재고 검증이 맞음이어야 한다. 원자적 UPDATE 는 SQL 한 문장이라 가장 단순하다. 비관적 락은 다른 주문이 줄을 서서 기다린다. 낙관적 락은 잠그지 않는 대신 충돌할 때마다 다시 시도하는 횟수(재시도)가 늘어난다. 시간 차이는 몇 ms 라서 의미를 두지 않는다',
    reference: '세 방식 모두 10건 성공 · 재고 맞음. 시간은 13~61ms, 낙관적 락은 재시도 176~267번',
    run: ['stock-atomic', 'stock-pessimistic', 'stock-optimistic'],
    show: ['stock-none', 'stock-atomic', 'stock-pessimistic', 'stock-optimistic'],
    condition: '재고 10 · 주문 100건 · 동시 50',
  },
  {
    id: 'scn-deadlock',
    no: 3,
    title: '데드락을 일부러 만들어 보기',
    why: '장바구니에 담은 순서는 사람마다 다르다. 주문 A 는 상품 1→2, 주문 B 는 상품 2→1 순서로 재고 행을 잠그면 서로 상대가 가진 행을 기다리다 영원히 풀리지 않는다. DB 가 한쪽을 골라 강제로 취소한다(오류 1213)',
    look: '데드락 수와 실패 수. 실패한 주문은 DB 가 취소한 것이라 재고는 맞아도 손님은 주문에 실패했다. 데드락이 나도 서버가 멈추지는 않고, 한쪽만 취소되고 나머지는 이어서 끝난다',
    reference: '주문 20건 중 10건 성공, 10건 데드락으로 실패. 재고는 맞음',
    run: ['deadlock-cross'],
    show: ['deadlock-cross'],
    condition: '상품 2개를 한 주문에 · 주문 20건(절반씩 반대 순서) · 동시 20',
  },
  {
    id: 'scn-prevent',
    no: 4,
    title: '데드락을 막는 방법',
    why: '데드락은 "서로 다른 순서로 잠그기" 때문에 생긴다. 모든 주문이 같은 순서(상품 번호 순)로 잠그면 서로 기다릴 수는 있어도 맞물려 멈출 수는 없다. 순서를 못 맞추는 경우를 위해 "밀려나면 다시 시도"도 비교한다',
    look: '순서를 맞추면 데드락 0, 실패 0. 다시 시도는 데드락을 없애지 못하고 밀려난 주문을 구해 줄 뿐이다(같은 순서로 또 부딪히면 또 밀려난다). 근본 해결은 잠그는 순서를 통일하는 것이다. 총 시간은 3번보다 길어 보일 수 있는데, 3번은 절반이 취소돼 일이 적었기 때문이다',
    reference: '순서 통일: 20건 모두 성공 · 데드락 0 · 약 0.23초. 다시 시도: 성공 11~14건, 데드락 55~70번, 5번을 다 써도 못 끝낸 주문 6~9건',
    run: ['deadlock-ordered', 'deadlock-retry'],
    show: ['deadlock-cross', 'deadlock-ordered', 'deadlock-retry'],
    condition: '3번과 같은 조건',
  },
  {
    id: 'scn-tx',
    no: 5,
    title: '트랜잭션은 짧게',
    why: '락은 트랜잭션이 끝날 때까지 잡혀 있다. 그 안에서 외부 결제 서버를 기다리면(여기서는 20ms) 그동안 다른 주문은 같은 상품 앞에서 줄을 선다',
    look: '총 시간. 결제를 안에서 하면 주문 수 × 결제 시간에 가까워지고, 밖에서 하면 동시에 처리되어 훨씬 짧다. 대신 밖에서 결제하면 재고가 없을 때 결제를 취소(환불)해야 하므로, 실제로는 재고를 먼저 잡아 두고 결제하는 식으로 설계한다',
    reference: '안에서: 약 1.1초, 밖에서: 35~111ms(주문 50건, 결제 20ms)',
    run: ['tx-long', 'tx-short'],
    show: ['tx-long', 'tx-short'],
    condition: '주문 50건 · 결제 20ms · 상품 1개',
  },
]

/** "순서대로 모두 실행"이 돌리는 실험(중복 없이). */
export const allIds = (): string[] => [...new Set(scenarios.flatMap((s) => s.run))]
