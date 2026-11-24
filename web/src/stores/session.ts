import { defineStore } from 'pinia'
import { ref } from 'vue'
import { Client } from '../api/client'
import type { SystemView, User } from '../api/types'

/** 로그인 상태와 api 호출기. 액세스 토큰은 여기 메모리에만 있다. 새로고침하면 쿠키로 다시 받는다. */
export const useSession = defineStore('session', () => {
  const user = ref<User | null>(null)
  const systems = ref<SystemView[]>([])
  const client = new Client(undefined, () => {
    user.value = null
    systems.value = []
  })

  async function loadMe(): Promise<void> {
    const me = await client.me()
    user.value = me.user
    systems.value = me.systems
  }

  /** 새로고침 뒤에 쿠키로 로그인을 되살린다. 실패하면 false. */
  async function restore(): Promise<boolean> {
    if (user.value) return true
    const token = client.hasToken() ? true : await client.refresh()
    if (!token) return false
    await loadMe()
    return true
  }

  async function login(email: string, password: string): Promise<void> {
    await client.login(email, password)
    await loadMe()
  }

  async function signup(email: string, password: string): Promise<void> {
    await client.signup(email, password)
    await login(email, password)
  }

  async function logout(): Promise<void> {
    await client.logout()
    user.value = null
    systems.value = []
  }

  function roleIn(systemId: number): SystemView['role'] | null {
    return systems.value.find((s) => s.id === systemId)?.role ?? null
  }

  return { user, systems, client, restore, login, signup, logout, loadMe, roleIn }
})
