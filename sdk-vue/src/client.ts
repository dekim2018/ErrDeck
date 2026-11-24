import { envelope, gzip, type Discarded } from './envelope'
import { Folder, uuid } from './fold'
import { parseStack } from './stack'
import type { Transport } from './transport'
import type { Breadcrumb, ErrDeckEvent, Options } from './types'

/**
 * 브라우저 SDK 의 중심(11.1). 에러를 잡으면 이벤트로 바꿔 길이가 정해진 큐에 넣고, 2초마다 묶어 보낸다.
 * 큐가 차면 기다리지 않고 버리고 센다. 429 와 503 의 Retry-After 동안은 보내지 않는다. SDK 안의 문제는 밖으로 던지지 않는다.
 */
export class Client {
  private queue: ErrDeckEvent[] = []
  private crumbs: Breadcrumb[] = []
  private discarded = new Map<string, number>()
  private pausedUntil = 0
  private pauseReason = 'rate_limited'
  private disabled = false
  private sending = false
  private readonly seen = new WeakSet<object>()
  private readonly folder: Folder
  private readonly queueSize: number
  private readonly maxBreadcrumbs: number

  constructor(
    private readonly url: string,
    private readonly options: Options,
    private readonly transport: Transport,
    private readonly now: () => number = () => Date.now(),
  ) {
    this.folder = new Folder(options.foldWindowMs ?? 10_000, options.foldThreshold ?? 5)
    this.queueSize = options.queueSize ?? 100
    this.maxBreadcrumbs = options.maxBreadcrumbs ?? 50
  }

  capture(error: unknown, tags?: Record<string, string>): void {
    try {
      if (error && typeof error === 'object') {
        if (this.seen.has(error)) return
        this.seen.add(error)
      }
      const event = this.event(error, tags)
      if (!this.folder.admit(event, this.now())) return
      this.enqueue(event)
    } catch {
      // 감시 대상의 화면을 SDK 가 깨뜨리지 않는다.
    }
  }

  addBreadcrumb(crumb: Omit<Breadcrumb, 'timestamp'>): void {
    this.crumbs.push({ timestamp: new Date(this.now()).toISOString(), ...crumb })
    if (this.crumbs.length > this.maxBreadcrumbs) this.crumbs.shift()
  }

  /** 모인 것을 압축해 보낸다. 보내는 중이면 다음 차례로 미룬다. */
  async flush(): Promise<void> {
    if (this.sending) return
    const events = this.take()
    if (!events) return
    this.sending = true
    try {
      const body = envelope(events.batch, events.discarded, new Date(this.now()))
      const compressed = await gzip(body).catch(() => null)
      const reply = await this.transport.send(compressed ? `${this.url}&enc=gzip` : this.url, compressed ?? body)
      this.handle(reply.status, reply.retryAfter, events)
    } catch {
      this.count('network_error', weight(events.batch))
      this.restore(events.discarded)
    } finally {
      this.sending = false
    }
  }

  /** 탭이 숨겨질 때 남은 것을 보낸다. 압축은 비동기라 페이지가 사라지기 전에 끝난다는 보장이 없어 압축하지 않는다. */
  flushBeacon(): void {
    try {
      const events = this.take()
      if (!events) return
      if (!this.transport.beacon(this.url, envelope(events.batch, events.discarded, new Date(this.now())))) {
        this.count('network_error', weight(events.batch))
        this.restore(events.discarded)
      }
    } catch {
      // 페이지가 사라지는 중이다. 할 수 있는 것이 없다.
    }
  }

  /** 시험용: 큐에 든 수. */
  pending(): number {
    return this.queue.length
  }

  private take(): { batch: ErrDeckEvent[]; discarded: Discarded[] } | null {
    this.queue.push(...this.folder.drain(this.now()))
    if (this.disabled) {
      this.count('unauthorized', weight(this.queue))
      this.queue = []
      return null
    }
    if (this.now() < this.pausedUntil) {
      this.count(this.pauseReason, weight(this.queue))
      this.queue = []
      return null
    }
    const discarded = [...this.discarded].map(([reason, quantity]) => ({ reason, category: 'event', quantity }))
    this.discarded.clear()
    if (!this.queue.length && !discarded.length) return null
    const batch = this.queue
    this.queue = []
    return { batch, discarded }
  }

  private handle(status: number, retryAfter: number | undefined, events: { batch: ErrDeckEvent[]; discarded: Discarded[] }) {
    if (status >= 200 && status < 300) return
    this.restore(events.discarded)
    const n = weight(events.batch)
    if (status === 429 || status === 503) {
      this.pauseReason = status === 429 ? 'rate_limited' : 'queue_full'
      this.pausedUntil = this.now() + (retryAfter ?? 60) * 1000
      this.count(this.pauseReason, n)
    } else if (status === 401 || status === 403) {
      // 키나 출처가 틀렸다. 고쳐질 때까지 보내 봐야 소용없다(5.4).
      this.disabled = true
      this.count('unauthorized', n)
      console.warn(`ErrDeck: ingest 가 ${status} 로 거절해 전송을 멈춘다. DSN 과 허용 출처를 확인한다`)
    } else {
      this.count(status === 413 ? 'too_large' : 'network_error', n)
    }
  }

  private enqueue(event: ErrDeckEvent) {
    if (this.queue.length >= this.queueSize) {
      this.count('queue_overflow', event.count ?? 1)
      return
    }
    this.queue.push(event)
  }

  private count(reason: string, quantity: number) {
    if (quantity > 0) this.discarded.set(reason, (this.discarded.get(reason) ?? 0) + quantity)
  }

  private restore(discarded: Discarded[]) {
    for (const d of discarded) this.count(d.reason, d.quantity)
  }

  private event(error: unknown, tags?: Record<string, string>): ErrDeckEvent {
    const err = error instanceof Error ? error : null
    const event: ErrDeckEvent = {
      event_id: uuid(),
      timestamp: new Date(this.now()).toISOString(),
      level: 'error',
      exceptions: [
        {
          type: err?.name || (typeof error === 'object' && error !== null ? 'Error' : 'UnhandledValue'),
          value: truncate(err ? err.message : describe(error)),
          frames: parseStack(err?.stack),
        },
      ],
      breadcrumbs: this.crumbs.slice(),
    }
    if (this.options.environment) event.environment = this.options.environment
    if (this.options.release) event.release = this.options.release
    if (tags && Object.keys(tags).length) event.tags = tags
    if (typeof location !== 'undefined') {
      // 쿼리와 조각은 보내지 않는다. 토큰이 실려 있을 수 있다(15.6).
      event.request = {
        method: 'GET',
        url: location.origin + location.pathname,
        headers: typeof navigator !== 'undefined' ? { 'User-Agent': navigator.userAgent } : {},
      }
    }
    return event
  }
}

function weight(events: ErrDeckEvent[]): number {
  return events.reduce((n, e) => n + (e.count ?? 1), 0)
}

function truncate(text: string): string {
  return text.length > 8192 ? text.slice(0, 8192) : text
}

function describe(value: unknown): string {
  try {
    return typeof value === 'string' ? value : JSON.stringify(value) ?? String(value)
  } catch {
    return String(value)
  }
}
