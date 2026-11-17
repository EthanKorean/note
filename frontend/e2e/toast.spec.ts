import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

const TOAST = 'http://127.0.0.1:9208/api/toast'
const send = (request: APIRequestContext, body: object) => request.post(TOAST, { data: body })

async function subscribers(request: APIRequestContext): Promise<number> {
  return (await (await request.get(`${TOAST}/status`)).json()).subscribers
}

/** 화면을 열고, 이 화면이 토스트를 구독할 때까지 기다린다(이미 열려 있는 다른 탭의 구독은 세지 않는다). */
async function open(page: Page, request: APIRequestContext, path = '/') {
  const before = await subscribers(request)
  await page.goto(`/#${path}`)
  await expect.poll(() => subscribers(request)).toBeGreaterThan(before)
}

test('토스트가 뜨고, 닫기 버튼 없이 누르면 닫힌다', async ({ page, request }) => {
  await open(page, request)
  await send(request, { kind: 'done', title: '빌드 끝', message: '테스트 통과', project: 'e2e-a' })
  const toast = page.locator('.toast', { hasText: '빌드 끝' })
  await expect(toast).toBeVisible()
  await expect(toast).toContainText('테스트 통과')
  await expect(toast).toContainText('e2e-a')
  await expect(toast.getByRole('button')).toHaveCount(0)
  await toast.click()
  await expect(toast).toBeHidden()
})

test('다른 화면으로 이동해도 알림을 받는다', async ({ page, request }) => {
  await open(page, request, '/suika')
  await send(request, { kind: 'info', title: '게임 중 알림', project: 'e2e-b' })
  await expect(page.locator('.toast', { hasText: '게임 중 알림' })).toBeVisible()
})

test('키보드: 포커스하고 Esc 나 Enter 로 닫는다', async ({ page, request }) => {
  await open(page, request)
  await send(request, { kind: 'attention', title: '키보드 A', project: 'e2e-k1' })
  await send(request, { kind: 'error', title: '키보드 B', project: 'e2e-k2' })
  const a = page.locator('.toast', { hasText: '키보드 A' })
  const b = page.locator('.toast', { hasText: '키보드 B' })
  await expect(a).toBeVisible()
  await expect(b).toBeVisible()
  await a.focus()
  await page.keyboard.press('Escape')
  await expect(a).toBeHidden()
  await b.focus()
  await page.keyboard.press('Enter')
  await expect(b).toBeHidden()
})

test('글자를 드래그해 고른 직후에는 닫히지 않는다', async ({ page, request }) => {
  await open(page, request)
  await send(request, { kind: 'attention', title: '복사하기', message: '이 문장을 드래그해서 복사', project: 'e2e-sel' })
  const toast = page.locator('.toast', { hasText: '복사하기' })
  await expect(toast).toBeVisible()
  await toast.locator('p').selectText()
  // 실제 클릭은 선택을 풀어 버리므로, 선택을 유지한 채 click 이벤트만 직접 보낸다
  await page.evaluate(() => {
    const p = document.querySelector('.toast p')!
    const r = document.createRange()
    r.selectNodeContents(p)
    const s = getSelection()!
    s.removeAllRanges()
    s.addRange(r)
    document.querySelector('.toast')!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  })
  await expect(toast).toBeVisible()
})

test('같은 프로젝트의 같은 종류는 교체되고, 다른 프로젝트는 따로 남는다', async ({ page, request }) => {
  await open(page, request)
  await send(request, { kind: 'attention', title: '교체', message: '첫번째', project: 'e2e-r1' })
  await expect(page.locator('.toast', { hasText: '첫번째' })).toBeVisible()
  await send(request, { kind: 'attention', title: '교체', message: '두번째', project: 'e2e-r1' })
  await expect(page.locator('.toast', { hasText: '두번째' })).toBeVisible()
  await expect(page.locator('.toast', { hasText: '첫번째' })).toHaveCount(0)
  await send(request, { kind: 'attention', title: '교체', message: '다른 프로젝트', project: 'e2e-r2' })
  await expect(page.locator('.toast.kind-attention')).toHaveCount(2)
})

test('동시에 5개까지만 보인다', async ({ page, request }) => {
  await open(page, request)
  for (let i = 1; i <= 7; i++) await send(request, { kind: 'info', title: `많음 ${i}`, project: `e2e-m${i}` })
  await expect(page.locator('.toast', { hasText: '많음 7' })).toBeVisible()
  await expect(page.locator('.toast')).toHaveCount(5)
})

test('완료는 10초 뒤에 사라지고, 확인 필요는 직접 닫을 때까지 남는다', async ({ page, request }) => {
  await page.clock.install()
  await open(page, request)
  await send(request, { kind: 'done', title: '곧 사라짐', project: 'e2e-t1' })
  await send(request, { kind: 'attention', title: '계속 남음', project: 'e2e-t2' })
  await expect(page.locator('.toast')).toHaveCount(2)
  await page.clock.fastForward(11_000)
  await expect(page.locator('.toast', { hasText: '곧 사라짐' })).toBeHidden()
  await expect(page.locator('.toast', { hasText: '계속 남음' })).toBeVisible()
})

test('탭이 가려져 있는 동안에는 완료 토스트가 닫히지 않고, 다시 보이면 10초 뒤에 닫힌다', async ({ page, request }) => {
  type HiddenWindow = { __hidden: boolean }
  await page.addInitScript(() => {
    const w = window as unknown as HiddenWindow
    w.__hidden = true
    Object.defineProperty(document, 'hidden', { get: () => w.__hidden })
  })
  await page.clock.install()
  await open(page, request)
  await send(request, { kind: 'done', title: '못 본 알림', project: 'e2e-h' })
  const toast = page.locator('.toast', { hasText: '못 본 알림' })
  await expect(toast).toBeVisible()
  await page.clock.fastForward(30_000) // 가려져 있는 동안은 10초가 훨씬 지나도 남는다
  await expect(toast).toBeVisible()
  await page.evaluate(() => {
    ;(window as unknown as HiddenWindow).__hidden = false
    document.dispatchEvent(new Event('visibilitychange'))
  })
  await expect(toast).toBeVisible() // 다시 보이는 순간부터 10초를 센다
  await page.clock.fastForward(11_000)
  await expect(toast).toBeHidden()
})

test('보이는 동안 뜬 완료 토스트도, 도중에 탭이 가려지면 멈췄다가 다시 보일 때부터 10초를 센다', async ({ page, request }) => {
  type HiddenWindow = { __hidden: boolean }
  const setHidden = (hidden: boolean) =>
    page.evaluate((h) => {
      ;(window as unknown as HiddenWindow).__hidden = h
      document.dispatchEvent(new Event('visibilitychange'))
    }, hidden)
  await page.addInitScript(() => {
    const w = window as unknown as HiddenWindow
    w.__hidden = false
    Object.defineProperty(document, 'hidden', { get: () => w.__hidden })
  })
  await page.clock.install()
  await open(page, request)
  await send(request, { kind: 'done', title: '보다 만 알림', project: 'e2e-h2' })
  const toast = page.locator('.toast', { hasText: '보다 만 알림' })
  await expect(toast).toBeVisible()
  await page.clock.fastForward(5_000)
  await setHidden(true)
  await page.clock.fastForward(30_000)
  await expect(toast).toBeVisible()
  await setHidden(false)
  await page.clock.fastForward(9_000)
  await expect(toast).toBeVisible()
  await page.clock.fastForward(2_000)
  await expect(toast).toBeHidden()
})

test('알림 내용은 글자로만 표시된다(HTML 이 실행되지 않는다)', async ({ page, request }) => {
  await open(page, request)
  const evil = '<img src=x onerror="window.__xss=1">'
  await send(request, { kind: 'info', title: '<b>굵게</b>', message: evil, project: 'e2e-x' })
  const toast = page.locator('.toast', { hasText: '굵게' })
  await expect(toast).toBeVisible()
  await expect(toast.locator('p')).toHaveText(evil)
  await expect(toast.locator('img, b')).toHaveCount(0)
  expect(await page.evaluate(() => (window as unknown as { __xss?: number }).__xss)).toBeUndefined()
})

type FakeWindow = { __notes?: { title: string; body?: string }[] }

test('알림 권한을 아직 정하지 않았으면 "브라우저 알림 허용" 버튼이 있고, 누르면 사라진다', async ({ page }) => {
  await page.addInitScript(() => {
    let permission = 'default'
    Object.defineProperty(Notification, 'permission', { get: () => permission })
    Notification.requestPermission = async () => {
      permission = 'granted'
      return 'granted'
    }
  })
  await page.goto('/')
  const button = page.getByRole('button', { name: '브라우저 알림 허용' })
  await expect(button).toBeVisible()
  await button.click()
  await expect(button).toBeHidden()
})

test('탭이 가려져 있고 권한이 있으면 브라우저(OS) 알림도 띄운다', async ({ page, request }) => {
  await page.addInitScript(() => {
    const w = window as unknown as FakeWindow & { Notification: unknown }
    w.__notes = []
    class FakeNotification {
      static permission = 'granted'
      static requestPermission = async () => 'granted'
      onclick: (() => void) | null = null
      constructor(title: string, options?: { body?: string }) {
        w.__notes!.push({ title, body: options?.body })
      }
      close() {}
    }
    w.Notification = FakeNotification
    Object.defineProperty(document, 'hidden', { get: () => true })
  })
  await open(page, request)
  await send(request, { kind: 'done', title: 'OS 알림', message: '내용', project: 'e2e-os' })
  await expect.poll(() => page.evaluate(() => (window as unknown as FakeWindow).__notes)).toEqual([{ title: 'OS 알림', body: '[e2e-os] 내용' }])
})

for (const scheme of ['light', 'dark'] as const) {
  test.describe(`모양 (${scheme})`, () => {
    // 스크린샷이 들어오는 애니메이션(반투명) 도중에 찍히지 않게 '동작 줄이기'로 고정한다.
    test.use({ colorScheme: scheme, viewport: { width: 1280, height: 720 }, reducedMotion: 'reduce' })

    test('네 종류 토스트와 긴 글이 보기 좋게 나온다', async ({ page, request }) => {
      await open(page, request, '/bulk-insert')
      await send(request, { kind: 'done', title: '작업이 끝났어요', message: '헬스체크 구현과 코드 리뷰를 마쳤어요', project: 'note' })
      await send(request, { kind: 'attention', title: '확인이 필요해요', message: 'Claude needs your permission to use Bash', project: 'other-project' })
      await send(request, { kind: 'error', title: '문제가 생겼어요', message: '아주 긴 오류 메시지가 줄바꿈되어 카드 안에 들어가는지 확인합니다 '.repeat(4), project: 'build' })
      await send(request, { kind: 'info', title: '알림', message: '일반 정보', project: 'e2e-shot' })
      await expect(page.locator('.toast')).toHaveCount(4)
      await page.screenshot({ path: `test-results/screens/toast-${scheme}.png` })
    })
  })
}

test.describe('모양 (좁은 화면)', () => {
  // 들어오는 애니메이션(오른쪽에서 밀려 들어옴) 도중에 위치를 재면 어긋나므로 '동작 줄이기'로 고정한다(그 경로도 함께 검증된다).
  test.use({ viewport: { width: 390, height: 800 }, reducedMotion: 'reduce' })

  test('좁은 화면에서도 가로로 넘치지 않는다', async ({ page, request }) => {
    await open(page, request)
    await send(request, { kind: 'attention', title: '확인이 필요해요', message: '긴_단어_'.repeat(30), project: 'e2e-mobile' })
    const toast = page.locator('.toast', { hasText: '확인이 필요해요' })
    await expect(toast).toBeVisible()
    // 동작 줄이기여도 Vue 가 들어오는 클래스(translateX)를 한 프레임은 붙이므로, 자리를 잡을 때까지 기다린 뒤 잰다.
    await expect
      .poll(async () => {
        const box = (await toast.boundingBox())!
        return box.x >= 0 && box.x + box.width <= 390
      })
      .toBe(true)
    await page.screenshot({ path: 'test-results/screens/toast-mobile.png' })
  })
})
