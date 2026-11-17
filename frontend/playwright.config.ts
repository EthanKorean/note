import { defineConfig } from '@playwright/test'

/**
 * 화면 검증(Playwright). 실행: `npm run e2e`
 * 백엔드(gateway, 각 서비스)와 toast-service(9208)는 미리 떠 있어야 한다. 프론트 dev 서버만 없으면 띄운다.
 * 토스트 테스트는 실제 toast-service 로 알림을 보내므로, 이미 열어 둔 Note 탭에도 알림이 뜬다.
 */
export default defineConfig({
  testDir: './e2e',
  workers: 1,
  timeout: 30_000,
  expect: { timeout: 5_000 },
  reporter: [['list']],
  outputDir: 'test-results',
  use: {
    baseURL: 'http://localhost:9201',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  webServer: { command: 'npm run dev', url: 'http://localhost:9201', reuseExistingServer: true },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
})
