import type { CodeBlock } from './methodSamples'

/** 실험별 핵심 코드(lock-service LockLab 에서 줄인 것). 직접 실행 카드에서 선택한 실험 옆에 보여 준다. */
export const lockCode: Record<string, CodeBlock[]> = {
  'stock-none': [
    {
      lang: 'java',
      title: '락 없음: 읽고, 계산하고, 그 값을 쓴다',
      code: `int stock = readStock(c, 1);           // SELECT stock FROM lock_product WHERE id = 1
if (stock < 1) return SOLD_OUT;
// 읽은 뒤 쓰기 전에 다른 주문이 재고를 바꿔도 모른다(갱신 손실)
update(c, "UPDATE lock_product SET stock = ? WHERE id = 1", stock - 1);
insertOrder(c, buyer);`,
    },
  ],
  'stock-atomic': [
    {
      lang: 'sql',
      title: '원자적 UPDATE: 확인과 차감을 한 문장으로',
      code: `UPDATE lock_product
   SET stock = stock - 1
 WHERE id = 1 AND stock >= 1;   -- 영향 행이 0이면 품절`,
    },
  ],
  'stock-pessimistic': [
    {
      lang: 'sql',
      title: '비관적 락: 읽을 때부터 행을 잠근다',
      code: `BEGIN;
SELECT stock FROM lock_product WHERE id = 1 FOR UPDATE;  -- 다른 주문은 여기서 기다린다
UPDATE lock_product SET stock = stock - 1 WHERE id = 1;
COMMIT;                                                  -- 이때 락이 풀린다`,
    },
  ],
  'stock-optimistic': [
    {
      lang: 'java',
      title: '낙관적 락: 버전을 확인하고, 충돌하면 다시',
      code: `for (int attempt = 1; attempt <= 10; attempt++) {
    // SELECT stock, version FROM lock_product WHERE id = 1   (잠그지 않는다)
    int updated = update(c,
        "UPDATE lock_product SET stock = stock - 1, version = version + 1 " +
        "WHERE id = 1 AND version = ?", version);
    if (updated == 1) return OK;   // 그 사이 아무도 안 바꿨다
    // 0 이면 다른 주문이 먼저 바꿨다 → 처음부터 다시(조금 쉬고)
}
return FAILED;`,
    },
  ],
  'deadlock-cross': [
    {
      lang: 'java',
      title: '서로 다른 순서로 잠그면 데드락',
      code: `// 주문 A: 상품 1 → 2,  주문 B: 상품 2 → 1
int[] order = buyer % 2 == 0 ? new int[]{1, 2} : new int[]{2, 1};
for (int id : order) {
    lockRow(c, id);   // SELECT … FOR UPDATE
    sleep(10);        // 가격 계산 같은 일이 끼는 틈
}
// A 는 1 을 쥐고 2 를, B 는 2 를 쥐고 1 을 기다린다 → 오류 1213`,
    },
  ],
  'deadlock-ordered': [
    {
      lang: 'java',
      title: '모두 같은 순서로 잠그기',
      code: `int[] order = {1, 2};   // 상품 번호 오름차순으로 항상 같게
for (int id : order) {
    lockRow(c, id);     // 기다릴 수는 있어도 서로 맞물려 멈추지는 않는다
}`,
    },
  ],
  'deadlock-retry': [
    {
      lang: 'java',
      title: '데드락이 나면 처음부터 다시',
      code: `for (int attempt = 1; ; attempt++) {
    try {
        return runTransaction(order);          // 순서는 그대로
    } catch (SQLException e) {
        if (!isDeadlock(e)) throw e;           // 오류 1213 만 다시 시도한다
        if (attempt >= 5) return FAILED;
        sleep(random(5, 30));                  // 같이 밀려난 주문과 다시 부딪히지 않게 흩는다
    }
}`,
    },
  ],
  'tx-long': [
    {
      lang: 'java',
      title: '결제를 트랜잭션 안에서: 락을 쥔 채로 기다린다',
      code: `BEGIN;
lockRow(c, 1);       // SELECT … FOR UPDATE
callPaymentServer(); // 결제를 기다리는 동안(기본 20ms) 다른 주문은 줄을 선다
update(c, "UPDATE lock_product SET stock = stock - 1 WHERE id = 1");
COMMIT;`,
    },
  ],
  'tx-short': [
    {
      lang: 'java',
      title: '결제를 트랜잭션 밖에서: 차감만 짧게',
      code: `callPaymentServer();  // 락 없이 동시에 진행
BEGIN;
update(c, "UPDATE lock_product SET stock = stock - 1 WHERE id = 1 AND stock >= 1");
COMMIT;               // 락은 이 짧은 구간에만`,
    },
  ],
}
