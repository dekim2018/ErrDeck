<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '../api/client'
import { useSession } from '../stores/session'

const session = useSession()
const router = useRouter()
const route = useRoute()
const mode = ref<'login' | 'signup'>('login')
const email = ref('')
const password = ref('')
const error = ref('')
const busy = ref(false)

async function submit() {
  error.value = ''
  busy.value = true
  try {
    if (mode.value === 'login') await session.login(email.value, password.value)
    else await session.signup(email.value, password.value)
    const next = typeof route.query.next === 'string' ? route.query.next : '/'
    await router.push(next)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '서버에 연결하지 못했다'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="login">
    <form class="card" @submit.prevent="submit">
      <h1>ErrDeck</h1>
      <p class="sub">에러, 성능, 가동 상태를 한곳에서</p>
      <label>이메일 <input v-model="email" type="email" autocomplete="username" required /></label>
      <label>
        비밀번호
        <input v-model="password" type="password" minlength="8" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" required />
      </label>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <button class="primary" type="submit" :disabled="busy">{{ mode === 'login' ? '로그인' : '가입하고 로그인' }}</button>
      <button class="link" type="button" @click="mode = mode === 'login' ? 'signup' : 'login'">
        {{ mode === 'login' ? '계정이 없다면 가입' : '이미 계정이 있다면 로그인' }}
      </button>
    </form>
  </div>
</template>
