import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 9201,
    // 같은 네트워크의 다른 기기(IP 주소)에서도 열 수 있게 모든 인터페이스에 연다
    host: true,
    // 개발 중 /api 요청을 Spring Boot(9203)로 전달 → CORS 설정 불필요
    proxy: {
      '/api': 'http://localhost:9203',
      // STOMP 웹소켓 (gateway 가 juso-service 로 중계)
      '/ws-juso': { target: 'ws://localhost:9203', ws: true },
      '/ws-image': { target: 'ws://localhost:9203', ws: true },
      '/ws-rag': { target: 'ws://localhost:9203', ws: true },
      '/ws-lock': { target: 'ws://localhost:9203', ws: true },
      '/ws-catch': { target: 'ws://localhost:9203', ws: true },
      // claude-toast 알림은 gateway 를 거치지 않고 toast-service(9208)로 바로 간다. 다른 서비스를 안 켜 놓아도 알림은 와야 해서다.
      // 주의: changeOrigin 을 켜지 말 것. toast-service 의 Host 검사(DNS 리바인딩 방어)가 브라우저의 Host 가 그대로 전달된다는 데 기대고 있다.
      '/ws-toast': { target: 'ws://127.0.0.1:9208', ws: true },
    },
  },
})
