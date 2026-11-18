import { expect, test } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'

/**
 * DB 락 전략 화면. lock-service(9209)와 gateway, MariaDB 가 떠 있어야 한다.
 * 실험은 서버에서 한 번에 하나만 돌고 결과가 test_result 에 쌓이므로 테스트는 순서대로(serial) 실행한다.
 * 실험은 연습용 lock_* 테이블만 바꾸고 기록만 쌓는다. 사용자의 기록을 지우지 않도록 "기록 지우기"는 누르지 않고,
 * 기록이 이미 얼마나 쌓여 있든 통과하도록 개수가 아니라 "방금 돌린 결과"(가장 위 행)로 확인한다.
 */
test.describe.configure({ mode: 'serial' })

let page: Page
const problems: string[] = []

/** 표의 한 행(방식 이름으로 찾는다). 실패 이유를 보여 주는 보조 행(.err-row)은 뺀다. */
const row = (scope: Locator, label: string) => scope.locator('table.lock-table tbody tr:not(.err-row)').filter({ hasText: label })

/** "1.13초", "43ms" → ms */
function toMs(text: string): number {
  const m = text.match(/([\d.]+)\s*(초|ms)/)
  if (!m) throw new Error(`시간을 읽을 수 없음: ${text}`)
  return m[2] === '초' ? parseFloat(m[1]) * 1000 : parseFloat(m[1])
}

/** 표 칸의 숫자. 열 순서: 방식, 성공, 품절, 실패, 데드락, 재시도, 재고 검증, 총 시간, 조건 */
const cell = async (r: Locator, n: number) => (await r.locator('td').nth(n).innerText()).trim()
const num = async (r: Locator, n: number) => parseInt(await cell(r, n), 10)

/** 서버가 실험 요청(/api/lock/run)에 응답한 횟수. 빠른 실험은 "실행 중…" 표시가 눈에 띌 만큼 오래 남지 않아서, 표시 대신 응답 수로 끝을 안다. */
let responses = 0

/** 시나리오의 실행 버튼을 눌러 실험 n 개가 모두 끝날 때까지 기다린다. */
async function runScenario(id: string, n: number) {
  const before = responses
  await page.locator(`#${id}`).getByRole('button', { name: /^(다시 )?실행$/ }).click()
  await expect.poll(() => responses - before, { timeout: 25_000 }).toBe(n)
  await expect(page.locator(`#${id}`).getByRole('button', { name: '실행 중…' })).toHaveCount(0)
}

test.beforeAll(async ({ browser }) => {
  page = await browser.newPage()
  page.on('console', (m) => m.type() === 'error' && problems.push(`console: ${m.text()}`))
  page.on('pageerror', (e) => problems.push(`pageerror: ${e.message}`))
  page.on('response', (r) => r.url().endsWith('/api/lock/run') && r.request().method() === 'POST' && responses++)
  page.on('response', (r) => r.status() >= 500 && problems.push(`${r.status()} ${r.url()}`))
  await page.goto('/#/lock')
  await expect(page.getByRole('heading', { level: 1, name: 'DB 락 전략' })).toBeVisible()
})

test.afterAll(async () => {
  await page?.close()
})

test('서비스가 연결되어 실행 버튼이 켜져 있다', async () => {
  // 상태 조회가 끝나야 버튼이 켜진다. 먼저 기다린 뒤에 오류 띠가 없는지 본다(기다리지 않으면 오류가 뜨기 전에 통과해 버린다).
  await expect(page.locator('#scn-none').getByRole('button', { name: /^(다시 )?실행$/ })).toBeEnabled()
  await expect(page.getByRole('alert')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '1~5번 순서대로 모두 실행' })).toBeEnabled()
})

test('1. 락 없이 주문하면 초과 판매가 생기고 재고가 어긋난다', async () => {
  await runScenario('scn-none', 1)
  const r = row(page.locator('#scn-none'), '락 없음')
  await expect(r).toHaveCount(1)
  expect(await num(r, 1)).toBeGreaterThan(10) // 성공한 주문이 재고(10)보다 많다
  await expect(r.locator('.verdict')).toContainText('어긋남')
  await expect(r.locator('.verdict')).toContainText('초과 판매')
  await expect(page.locator('#scn-none .tag').first()).toHaveText('결과 있음')
})

test('2. 세 가지 락 방식은 10건만 팔고 재고가 맞는다', async () => {
  await runScenario('scn-stock', 3)
  const scope = page.locator('#scn-stock')
  for (const label of ['원자적 UPDATE', '비관적 락', '낙관적 락']) {
    const r = row(scope, label)
    expect(await num(r, 1), `${label} 성공 수`).toBe(10)
    expect(await num(r, 2), `${label} 품절 수`).toBe(90)
    await expect(r.locator('.verdict')).toHaveText('맞음')
  }
  expect(await num(row(scope, '낙관적 락'), 5), '낙관적 락 재시도').toBeGreaterThan(0)
  await expect(row(scope, '락 없음').locator('.verdict')).toContainText('어긋남') // 1번 결과가 비교용으로 같이 보인다
})

test('3. 서로 다른 순서로 잠그면 데드락이 난다(재고는 맞음)', async () => {
  await runScenario('scn-deadlock', 1)
  const r = row(page.locator('#scn-deadlock'), '서로 다른 순서')
  expect(await num(r, 4), '데드락').toBeGreaterThan(0)
  expect(await num(r, 3), '실패').toBeGreaterThan(0)
  expect(await num(r, 1) + (await num(r, 3)), '성공 + 실패 = 주문 20건').toBe(20)
  await expect(r.locator('.verdict')).toHaveText('맞음')
  await expect(page.locator('#scn-deadlock .err-row')).toContainText('데드락이 나서 DB 가 이 주문을 취소했습니다')
})

test('4. 순서를 통일하면 데드락이 없고, 다시 시도는 데드락을 없애지 못한다', async () => {
  await runScenario('scn-prevent', 2)
  const scope = page.locator('#scn-prevent')
  const ordered = row(scope, '상품 번호 순서')
  expect(await num(ordered, 1), '성공').toBe(20)
  expect(await num(ordered, 3), '실패').toBe(0)
  expect(await num(ordered, 4), '데드락').toBe(0)
  await expect(ordered.locator('.verdict')).toHaveText('맞음')
  const retry = row(scope, '다시 시도')
  expect(await num(retry, 4), '다시 시도 방식의 데드락').toBeGreaterThan(0)
  expect(await num(retry, 5), '재시도').toBeGreaterThan(0)
  await expect(retry.locator('.verdict')).toHaveText('맞음')
  await expect(row(scope, '서로 다른 순서')).toHaveCount(1) // 3번 결과가 비교용으로 같이 보인다
})

test('5. 결제를 트랜잭션 밖에서 하면 훨씬 빠르다', async () => {
  await runScenario('scn-tx', 2)
  const scope = page.locator('#scn-tx')
  const inside = row(scope, '트랜잭션 안에서')
  const outside = row(scope, '트랜잭션 밖에서')
  expect(await num(inside, 1)).toBe(50)
  expect(await num(outside, 1)).toBe(50)
  await expect(inside.locator('.verdict')).toHaveText('맞음')
  await expect(outside.locator('.verdict')).toHaveText('맞음')
  const tIn = toMs(await cell(inside, 7))
  const tOut = toMs(await cell(outside, 7))
  expect(tIn, `안 ${tIn}ms 가 밖 ${tOut}ms 보다 5배 이상 느려야 한다`).toBeGreaterThan(tOut * 5)
  // 총 시간이 가장 짧은 행에 최단, 가장 긴 행에 최장이 붙는다(화면 포맷 규칙).
  await expect(outside.locator('.best-tag')).toHaveText('최단')
  await expect(inside.locator('.worst-tag')).toHaveText('최장')
})

test('실험 기록 표에는 방금 돌린 결과가 가장 위에 있다', async () => {
  const first = page.locator('#card-results table.lock-table tbody tr:not(.err-row)').first()
  await expect(first).toContainText('결제를 트랜잭션 밖에서') // 5번에서 마지막으로 돌린 실험
  await expect(first).toContainText('주문 50건')
})

test('직접 실행: 실험을 바꾸면 주문 수가 그 실험의 기본값으로 바뀐다', async () => {
  const card = page.locator('#card-custom')
  const buyers = card.getByRole('spinbutton', { name: /^주문자 수/ })
  await card.getByRole('radio', { name: /상품 번호 순서/ }).check()
  await expect(buyers).toHaveValue('20')
  await card.getByRole('radio', { name: /결제를 트랜잭션 밖에서/ }).check()
  await expect(buyers).toHaveValue('50')
  await card.getByRole('radio', { name: /^락 없음/ }).check()
  await expect(buyers).toHaveValue('100')
  // 고른 실험의 핵심 코드가 오른쪽에 나온다.
  await expect(card.locator('.codeblock')).toContainText('갱신 손실')
  await card.getByRole('radio', { name: /비관적 락/ }).check()
  await expect(card.locator('.codeblock')).toContainText('FOR UPDATE')
})

test('직접 실행: 잘못된 입력은 거절하고, 올바른 입력은 실행한다', async () => {
  const card = page.locator('#card-custom')
  await card.getByRole('spinbutton', { name: /^주문자 수/ }).fill('1')
  await card.getByRole('button', { name: '실행', exact: true }).click()
  await expect(card.getByRole('alert')).toContainText('주문자 수는 2 ~ 200') // 오류는 이 카드 안에 나온다
  await expect(page.locator('.page > .error')).toHaveCount(0) // 화면 맨 위의 오류 띠에는 올라가지 않는다
  // 다른 입력으로 바꾸면 오류가 사라지게 하지는 않지만, 올바른 값으로 실행하면 오류가 지워진다.
  await card.getByRole('radio', { name: /원자적 UPDATE/ }).check() // 실험을 고르면 주문 수가 기본값으로 돌아가므로 먼저 고른다
  await card.getByRole('spinbutton', { name: /^주문자 수/ }).fill('30')
  await card.getByRole('spinbutton', { name: /^재고/ }).fill('5')
  await card.getByRole('button', { name: '실행', exact: true }).click()
  await expect(card.getByRole('alert')).toHaveCount(0, { timeout: 15_000 }) // 올바른 값으로 실행하면 입력 오류가 지워진다
  const latest = page.locator('#card-results table.lock-table tbody tr:not(.err-row)').first()
  await expect(latest).toContainText('원자적 UPDATE')
  expect(await num(latest, 1)).toBe(5) // 재고 5 → 5건 성공
  expect(await num(latest, 2)).toBe(25) // 30 - 5 = 25건 품절
  await expect(latest).toContainText('주문 30건')
  await expect(latest).toContainText('재고 5')
  // 조건을 바꿔 돌린 결과는 시나리오 표를 대신하지 않는다(시나리오는 기본 조건으로 돌린 결과만 보여 준다).
  expect(await num(row(page.locator('#scn-stock'), '원자적 UPDATE'), 1)).toBe(10)
})

test('새로고침해도 기록이 서버에서 다시 불러와진다', async () => {
  await page.reload()
  const first = page.locator('#card-results table.lock-table tbody tr:not(.err-row)').first()
  await expect(first).toContainText('원자적 UPDATE') // 직전에 직접 실행한 결과(주문 30건)
  await expect(first).toContainText('주문 30건')
  await expect(page.locator('#scn-none .tag').first()).toHaveText('결과 있음')
})

test('모두 실행 버튼은 5개 시나리오를 순서대로 돌리고 진행 상황을 보여 준다', async () => {
  await page.getByRole('button', { name: '1~5번 순서대로 모두 실행' }).click()
  await expect(page.locator('#card-scenario .loading')).toContainText(/\d\/9/)
  // 실행하는 동안 다른 실행 버튼은 잠긴다.
  await expect(page.locator('#scn-none').getByRole('button', { name: /실행$/ })).toBeDisabled()
  await expect(page.locator('#card-scenario .loading')).toHaveCount(0, { timeout: 30_000 })
  // 순서대로 돌았으므로 마지막 실험(결제를 밖에서)이 가장 위에 있다.
  await expect(page.locator('#card-results table.lock-table tbody tr:not(.err-row)').first()).toContainText('결제를 트랜잭션 밖에서')
})

test('화면을 쓰는 동안 콘솔 오류와 서버 오류가 없었다', async () => {
  expect(problems).toEqual([])
})
