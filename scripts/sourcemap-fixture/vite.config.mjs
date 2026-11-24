import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// 운영과 같은 숨은 소스맵(7.2). 결과는 dist 에 둔다.
export default defineConfig({
  plugins: [vue()],
  build: { sourcemap: 'hidden', outDir: 'dist', emptyOutDir: true },
})
