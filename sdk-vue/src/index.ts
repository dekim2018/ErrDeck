import type { App, ComponentPublicInstance } from 'vue'
import { Client } from './client'
import { ingestUrl } from './dsn'
import { browserTransport } from './transport'
import type { Options } from './types'

export type { Options } from './types'
export { Client } from './client'

let current: Client | null = null

/** 코드에서 직접 보낸다. SDK 가 꺼져 있으면 아무것도 하지 않는다. */
export function captureException(error: unknown, tags?: Record<string, string>): void {
  current?.capture(error, tags)
}

/**
 * Vue 플러그인(11.3).
 *
 * ```ts
 * app.use(createErrDeck({ dsn: import.meta.env.VITE_ERRDECK_DSN, release: __RELEASE__, environment: import.meta.env.MODE, router }))
 * ```
 */
export function createErrDeck(options: Options) {
  return {
    install(app: App) {
      if (!options.dsn || typeof window === 'undefined') return
      let url: string
      try {
        url = ingestUrl(options.dsn)
      } catch (e) {
        console.warn(String(e))
        return
      }
      const client = new Client(url, options, browserTransport)
      current = client

      const previous = app.config.errorHandler
      app.config.errorHandler = (err: unknown, instance: ComponentPublicInstance | null, info: string) => {
        const component = instance?.$options?.name ?? (instance?.$ as { type?: { __name?: string } } | undefined)?.type?.__name
        client.capture(err, { ...(component ? { component } : {}), ...(info ? { lifecycle: info } : {}) })
        if (previous) previous(err, instance, info)
        else console.error(err)
      }
      window.addEventListener('error', (event) => client.capture(event.error ?? event.message))
      window.addEventListener('unhandledrejection', (event) => client.capture(event.reason))

      if (options.breadcrumbs !== false) watchBreadcrumbs(client, url, options)

      const timer = setInterval(() => void client.flush(), options.flushIntervalMs ?? 2000)
      document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'hidden') client.flushBeacon()
      })
      window.addEventListener('pagehide', () => client.flushBeacon())
      const unmount = app.unmount.bind(app)
      app.unmount = () => {
        clearInterval(timer)
        client.flushBeacon()
        unmount()
      }
    },
  }
}

/** 라우트 이동, 클릭한 요소, API 호출의 주소와 상태 코드(11.3). */
function watchBreadcrumbs(client: Client, ingest: string, options: Options) {
  options.router?.afterEach((to, from) => client.addBreadcrumb({ category: 'navigation', data: { from: from.fullPath, to: to.fullPath } }))
  document.addEventListener(
    'click',
    (event) => {
      const el = event.target instanceof Element ? event.target : null
      if (!el) return
      const text = (el.textContent ?? '').trim().slice(0, 40)
      const id = el.id ? `#${el.id}` : ''
      const cls = typeof el.className === 'string' && el.className ? `.${el.className.trim().split(/\s+/).join('.')}` : ''
      client.addBreadcrumb({ category: 'ui.click', message: `${el.tagName.toLowerCase()}${id}${cls}${text ? ` "${text}"` : ''}` })
    },
    true,
  )
  const original = window.fetch.bind(window)
  window.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    if (url.startsWith(ingest.split('?')[0] ?? ingest)) return original(input, init)
    const method = init?.method ?? (input instanceof Request ? input.method : 'GET')
    try {
      const response = await original(input, init)
      client.addBreadcrumb({ category: 'http', message: `${method} ${url.split('?')[0]}`, data: { status_code: String(response.status) } })
      return response
    } catch (e) {
      client.addBreadcrumb({ category: 'http', message: `${method} ${url.split('?')[0]}`, data: { error: String(e) } })
      throw e
    }
  }
}
