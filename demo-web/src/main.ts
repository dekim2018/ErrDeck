import { createErrDeck } from '@errdeck/sdk-vue'
import { createApp } from 'vue'
import App from './App.vue'

// 데모 프로젝트(scripts/demo/seed.sql 의 9002)의 DSN. 허용 출처는 http://localhost:8092 와 5173 이다.
const dsn = import.meta.env.VITE_ERRDECK_DSN ?? 'http://demowebkey@localhost:8080/9002'

createApp(App)
  .use(createErrDeck({ dsn, release: __RELEASE__, environment: import.meta.env.MODE }))
  .mount('#app')
