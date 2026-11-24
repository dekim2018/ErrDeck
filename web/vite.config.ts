import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// 개발 서버는 /api 를 api 프로세스로 넘긴다. 브라우저에는 같은 출처로 보여 리프레시 쿠키(SameSite=Strict)가 그대로 실린다.
const api = process.env.ERRDECK_API ?? 'http://localhost:8081'

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': { target: api, changeOrigin: false },
    },
  },
})
