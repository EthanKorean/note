import { expect, test } from '@playwright/test'

const pages = [
  { path: '/', h1: 'Note', title: 'Note' },
  { path: '/bulk-insert', h1: '대용량 적재', title: '대용량 적재 · Note' },
  { path: '/image-cache', h1: '이미지 캐싱', title: '이미지 캐싱 · Note' },
  { path: '/ai-terms', h1: 'AI 용어 정리', title: 'AI 용어 정리 · Note' },
  { path: '/rag', h1: 'RAG 검색', title: 'RAG 검색 · Note' },
  { path: '/lock', h1: 'DB 락 전략', title: 'DB 락 전략 · Note' },
  { path: '/suika', h1: '수박 게임', title: '수박 게임 · Note' },
  { path: '/catch', h1: '캐치마인드', title: '캐치마인드 · Note' },
]

for (const p of pages) {
  test(`${p.h1} 화면이 오류 없이 열린다`, async ({ page }) => {
    const problems: string[] = []
    page.on('console', (m) => m.type() === 'error' && problems.push(`console: ${m.text()}`))
    page.on('pageerror', (e) => problems.push(`pageerror: ${e.message}`))
    page.on('response', (r) => r.status() >= 500 && problems.push(`${r.status()} ${r.url()}`))

    await page.goto(`/#${p.path}`)
    await expect(page.getByRole('heading', { level: 1, name: p.h1 })).toBeVisible()
    await expect(page).toHaveTitle(p.title)
    await page.waitForLoadState('networkidle')
    expect(problems).toEqual([])
  })
}

test('사이드 메뉴로 모든 화면에 이동한다', async ({ page }) => {
  await page.goto('/')
  for (const p of pages.slice(1)) {
    await page.getByRole('navigation', { name: '메뉴' }).getByRole('link', { name: p.h1 }).click()
    await expect(page.getByRole('heading', { level: 1, name: p.h1 })).toBeVisible()
  }
})
