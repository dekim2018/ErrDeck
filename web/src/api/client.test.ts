import { describe, expect, it, vi } from 'vitest'
import { ApiError, Client } from './client'

function json(status: number, body: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('Client', () => {
  it('401 을 받으면 쿠키로 한 번 재발급하고 같은 요청을 다시 보낸다', async () => {
    const calls: string[] = []
    const fetcher = vi.fn(async (path: string | URL | Request, init?: RequestInit) => {
      const auth = new Headers(init?.headers).get('Authorization')
      calls.push(`${String(path)} ${auth ?? ''}`)
      if (path === '/api/auth/refresh') return json(200, { accessToken: 'new', expiresAt: '', user: { id: 1, email: 'a', admin: false } })
      if (auth === 'Bearer new') return json(200, { user: { id: 1 }, systems: [] })
      return json(401, { detail: '만료' })
    })
    const client = new Client(fetcher as typeof fetch)
    client.setToken('old')

    const me = await client.me()

    expect(me.systems).toEqual([])
    expect(calls).toEqual(['/api/me Bearer old', '/api/auth/refresh ', '/api/me Bearer new'])
  })

  it('동시에 여러 요청이 401 을 받아도 재발급은 한 번만 한다', async () => {
    let refreshes = 0
    const fetcher = vi.fn(async (path: string | URL | Request, init?: RequestInit) => {
      if (path === '/api/auth/refresh') {
        refreshes++
        await new Promise((r) => setTimeout(r, 10))
        return json(200, { accessToken: 'new', expiresAt: '', user: { id: 1, email: 'a', admin: false } })
      }
      return new Headers(init?.headers).get('Authorization') === 'Bearer new' ? json(200, []) : json(401, {})
    })
    const client = new Client(fetcher as typeof fetch)
    client.setToken('old')

    await Promise.all([client.projects(1), client.projects(2), client.projects(3)])

    expect(refreshes).toBe(1)
  })

  it('재발급도 실패하면 로그아웃을 알리고 401 오류를 던진다', async () => {
    const onSignedOut = vi.fn()
    const client = new Client((async () => json(401, { detail: '만료' })) as typeof fetch, onSignedOut)
    client.setToken('old')

    await expect(client.me()).rejects.toEqual(new ApiError(401, '다시 로그인해야 한다'))
    expect(onSignedOut).toHaveBeenCalledOnce()
  })

  it('실패 응답의 detail 을 오류 메시지로 쓴다', async () => {
    const client = new Client((async () => json(403, { detail: 'MEMBER 이상만 할 수 있다' })) as typeof fetch)
    client.setToken('t')

    await expect(client.changeStatus(1, 'resolved')).rejects.toMatchObject({ status: 403, message: 'MEMBER 이상만 할 수 있다' })
  })
})
