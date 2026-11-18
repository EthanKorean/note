import { expect, test } from '@playwright/test'

/** lock-service API(gateway 경유). 잘못된 요청은 거절하고, 실험은 한 번에 하나만 돌린다. */
const post = (request: import('@playwright/test').APIRequestContext, data: unknown) =>
  request.post('/api/lock/run', { data })

test('모르는 실험 id 는 400', async ({ request }) => {
  const res = await post(request, { id: 'nope' })
  expect(res.status()).toBe(400)
  expect((await res.json()).error).toContain('알 수 없는 실험')
})

test('범위를 벗어난 값은 400', async ({ request }) => {
  for (const body of [{ id: 'stock-none', buyers: 1 }, { id: 'stock-none', buyers: 201 }, { id: 'stock-none', stock: 0 }, { id: 'tx-long', payMs: 101 }]) {
    const res = await post(request, body)
    expect(res.status(), JSON.stringify(body)).toBe(400)
  }
})

test('그 실험에서 쓰지 않는 값은 범위를 검사하지 않는다(무시한다)', async ({ request }) => {
  // 재고 차감 실험에 결제 시간 200 을 보내도 무시되어 실행된다.
  const res = await post(request, { id: 'stock-atomic', payMs: 200 })
  expect(res.status()).toBe(200)
  expect((await res.json()).payMs).toBe(0)
})

test('JSON 이 아닌 본문은 400, 응답에 내부 정보가 없다', async ({ request }) => {
  const res = await request.post('/api/lock/run', { headers: { 'Content-Type': 'application/json' }, data: '{깨진' })
  expect(res.status()).toBe(400)
  expect(JSON.stringify(await res.json())).not.toMatch(/jdbc|localhost|\/home\//i)
})

test('status 는 실험 9개를 알려 주고 오류가 없다', async ({ request }) => {
  const s = await (await request.get('/api/lock/status')).json()
  expect(s.error).toBeNull()
  expect(s.experiments).toHaveLength(9)
  expect(s.running).toBe(false)
})

test('실험이 도는 동안 다른 실험은 409 로 거절한다', async ({ request }) => {
  const first = post(request, { id: 'tx-long', buyers: 60, payMs: 30 }) // 약 2초
  await expect.poll(async () => (await (await request.get('/api/lock/status')).json()).running, { timeout: 5_000 }).toBe(true)
  const second = await post(request, { id: 'stock-atomic' })
  expect(second.status()).toBe(409)
  expect((await second.json()).error).toContain('실행 중')
  const done = await first
  expect(done.status()).toBe(200)
  expect((await done.json()).consistent).toBe(true)
  // 끝나면 다시 실행할 수 있다.
  expect((await post(request, { id: 'stock-atomic' })).status()).toBe(200)
})
