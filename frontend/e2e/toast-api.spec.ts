import { expect, test } from '@playwright/test'

const TOAST = 'http://127.0.0.1:9208/api/toast'

test('JSON 이 아닌 요청은 415 로 거부한다', async ({ request }) => {
  const r = await request.post(TOAST, { headers: { 'Content-Type': 'text/plain' }, data: '{"kind":"done"}' })
  expect(r.status()).toBe(415)
})

test('모르는 kind 는 400 이다', async ({ request }) => {
  const r = await request.post(TOAST, { data: { kind: 'hack' } })
  expect(r.status()).toBe(400)
  expect((await r.json()).error).toContain('kind')
})

test('kind 를 생략해도 받고, 응답에 id 와 구독자 수가 있다', async ({ request }) => {
  const r = await request.post(TOAST, { data: {} })
  expect(r.status()).toBe(200)
  const body = await r.json()
  expect(typeof body.id).toBe('string')
  expect(typeof body.subscribers).toBe('number')
})

test('status 가 구독자 수를 준다', async ({ request }) => {
  const r = await request.get(`${TOAST}/status`)
  expect(r.status()).toBe(200)
  expect(typeof (await r.json()).subscribers).toBe('number')
})

test('Host 가 localhost 계열이 아니면 403 이다(DNS 리바인딩 방어)', async ({ request }) => {
  for (const host of ['evil.example', 'localhost.evil.example', '127.0.0.1.evil.example']) {
    const r = await request.get(`${TOAST}/status`, { headers: { Host: host } })
    expect(r.status(), host).toBe(403)
  }
  expect((await request.get(`${TOAST}/status`, { headers: { Host: 'localhost:9208' } })).status()).toBe(200)
})
