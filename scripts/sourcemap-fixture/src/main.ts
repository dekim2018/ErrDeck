import { createApp } from 'vue'
import App from './App.vue'
import { applyRemote } from './collab'

// 해석기 검증용 작은 앱. 함수 이름, 화살표 함수, 클래스, 한글 문자열을 골고루 섞는다.
const app = createApp(App)
app.config.errorHandler = (err) => console.error('잡은 에러', err)
app.mount('#app')

window.addEventListener('keydown', (event) => {
  if (event.key === 'x') {
    applyRemote({ ops: undefined as unknown as string[] })
  }
})
