import { defineConfig } from 'vitest/config'

// 라이브러리 한 파일로 만든다. vue 는 감시 대상의 것을 쓰므로 담지 않는다.
export default defineConfig({
  build: {
    lib: { entry: 'src/index.ts', formats: ['es'], fileName: () => 'errdeck-vue.js' },
    rollupOptions: { external: ['vue'] },
    emptyOutDir: false,
    minify: true,
  },
  test: {
    include: ['src/**/*.test.ts'],
  },
})
