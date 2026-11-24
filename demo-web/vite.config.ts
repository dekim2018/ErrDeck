import { execSync } from 'node:child_process'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// 릴리스 이름은 커밋 해시다. 소스맵 업로드(15)와 같은 값을 SDK 에 넣는다(7.2).
function release(): string {
  if (process.env.RELEASE) return process.env.RELEASE
  try {
    return `demo-web@${execSync('git rev-parse --short HEAD').toString().trim()}`
  } catch {
    return 'demo-web@dev'
  }
}

export default defineConfig({
  plugins: [vue()],
  define: { __RELEASE__: JSON.stringify(release()) },
  // 운영과 같이 소스맵은 만들되 번들에 주소를 적지 않는다(7.2). 소스맵은 15 작업에서 api 에 올린다.
  build: { sourcemap: 'hidden' },
})
