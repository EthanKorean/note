import { expect, test } from '@playwright/test'

/** 왼쪽 메뉴(LNB): 그룹 접기/펼치기. 상태는 이 브라우저의 localStorage 에만 저장된다. */
const nav = (page: import('@playwright/test').Page) => page.getByRole('navigation', { name: '메뉴' })

test('그룹 제목을 누르면 항목이 접히고 다시 누르면 펼쳐진다', async ({ page }) => {
  await page.goto('/#/')
  const game = nav(page).getByRole('button', { name: /^게임/ })
  await expect(game).toHaveAttribute('aria-expanded', 'true')
  await expect(nav(page).getByRole('link', { name: '수박 게임' })).toBeVisible()
  await game.click()
  await expect(game).toHaveAttribute('aria-expanded', 'false')
  await expect(nav(page).getByRole('link', { name: '수박 게임' })).toBeHidden()
  await expect(nav(page).getByRole('link', { name: '대용량 적재' })).toBeVisible() // 다른 그룹은 그대로
  await game.click()
  await expect(nav(page).getByRole('link', { name: '수박 게임' })).toBeVisible()
})

test('접은 상태는 새로고침해도 유지되고, 접힌 그룹의 화면으로 가면 자동으로 펼쳐진다', async ({ page }) => {
  await page.goto('/#/')
  await nav(page).getByRole('button', { name: /^게임/ }).click()
  await page.reload()
  await expect(nav(page).getByRole('button', { name: /^게임/ })).toHaveAttribute('aria-expanded', 'false')
  await page.goto('/#/suika')
  await expect(nav(page).getByRole('button', { name: /^게임/ })).toHaveAttribute('aria-expanded', 'true')
  await expect(nav(page).getByRole('link', { name: '수박 게임' })).toBeVisible()
  // 자동으로 펼친 것도 저장되어, 다른 그룹을 눌러도 이 그룹이 되돌아가 접히지 않는다.
  await nav(page).getByRole('button', { name: /^캐시/ }).click()
  await page.reload()
  await expect(nav(page).getByRole('button', { name: /^게임/ })).toHaveAttribute('aria-expanded', 'true')
  await expect(nav(page).getByRole('button', { name: /^캐시/ })).toHaveAttribute('aria-expanded', 'false')
})

test('저장된 값이 깨져 있어도 메뉴가 그대로 나온다', async ({ page }) => {
  for (const bad of ['null', '5', '"x"', '[1]', '{깨진', '{"AI":"yes"}']) {
    await page.goto('/#/')
    await page.evaluate((v) => localStorage.setItem('note.menu.collapsed', v), bad)
    await page.reload()
    await expect(nav(page).getByRole('link', { name: '수박 게임' }), `저장값 ${bad}`).toBeVisible()
    await expect(nav(page).getByRole('button', { name: /^AI/ })).toHaveAttribute('aria-expanded', 'true')
  }
})

test('좁은 화면에서는 접지 않고 항목을 모두 보여 준다', async ({ page }) => {
  await page.setViewportSize({ width: 420, height: 800 })
  await page.goto('/#/')
  await page.evaluate(() => localStorage.setItem('note.menu.collapsed', JSON.stringify({ 게임: true })))
  await page.reload()
  await expect(nav(page).getByRole('link', { name: '수박 게임' })).toBeVisible()
})
