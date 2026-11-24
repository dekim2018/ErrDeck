import { gunzipSync } from 'node:zlib'
import { describe, expect, it } from 'vitest'
import { Client } from './client'
import { ingestUrl } from './dsn'
import { envelope } from './envelope'
import { parseStack } from './stack'
import type { Reply, Transport } from './transport'

/** 받은 봉투를 풀어 둔다. 응답은 순서대로 정할 수 있다. */
class FakeTransport implements Transport {
  sent: { url: string; lines: string[] }[] = []
  beacons: { url: string; lines: string[] }[] = []
  replies: Reply[] = []

  async send(url: string, body: Uint8Array): Promise<Reply> {
    const raw = url.includes('enc=gzip') ? gunzipSync(body) : Buffer.from(body)
    this.sent.push({ url, lines: raw.toString('utf8').trimEnd().split('\n') })
    return this.replies.shift() ?? { status: 202 }
  }

  beacon(url: string, body: Uint8Array): boolean {
    this.beacons.push({ url, lines: Buffer.from(body).toString('utf8').trimEnd().split('\n') })
    return true
  }

  events(): Record<string, unknown>[] {
    return this.sent.flatMap((s) => items(s.lines, 'event'))
  }

  discarded(reason: string): number {
    return [...this.sent, ...this.beacons]
      .flatMap((s) => items(s.lines, 'client_report'))
      .flatMap((r) => r.discarded as { reason: string; quantity: number }[])
      .filter((d) => d.reason === reason)
      .reduce((n, d) => n + d.quantity, 0)
  }
}

function items(lines: string[], type: string): Record<string, unknown>[] {
  const out: Record<string, unknown>[] = []
  for (let i = 1; i < lines.length; i += 2) {
    if (JSON.parse(lines[i] ?? '{}').type === type) out.push(JSON.parse(lines[i + 1] ?? '{}'))
  }
  return out
}

const URL = 'https://ingest.example.com/ingest/9002/envelope?key=demowebkey'

function clock(start = Date.parse('2026-11-24T10:00:00Z')) {
  const c = { t: start, now: () => c.t }
  return c
}

describe('ingestUrl', () => {
  it('키를 헤더가 아니라 쿼리로 싣는다', () => {
    expect(ingestUrl('https://demowebkey@ingest.example.com/9002')).toBe(URL)
    expect(ingestUrl('http://k@localhost:8080/1')).toBe('http://localhost:8080/ingest/1/envelope?key=k')
  })

  it('형식이 틀리면 던진다', () => {
    expect(() => ingestUrl('https://ingest.example.com/9002')).toThrow()
    expect(() => ingestUrl('ftp://k@h/1')).toThrow()
  })
})

describe('parseStack', () => {
  it('크롬의 스택을 읽는다', () => {
    const stack = `TypeError: Cannot read properties of undefined (reading 'length')
    at applyRemote (https://copage.example.com/assets/index-a7VI5c4k.js:1:48213)
    at https://copage.example.com/assets/index-a7VI5c4k.js:1:50000
    at async Proxy.onClick (https://copage.example.com/assets/index-a7VI5c4k.js:2:10)`

    expect(parseStack(stack)).toEqual([
      { function: 'applyRemote', filename: 'https://copage.example.com/assets/index-a7VI5c4k.js', lineno: 1, colno: 48213 },
      { filename: 'https://copage.example.com/assets/index-a7VI5c4k.js', lineno: 1, colno: 50000 },
      { function: 'Proxy.onClick', filename: 'https://copage.example.com/assets/index-a7VI5c4k.js', lineno: 2, colno: 10 },
    ])
  })

  it('파이어폭스와 사파리의 스택을 읽는다', () => {
    const stack = `applyRemote@https://x.example.com/assets/a.js:1:48213
@https://x.example.com/assets/a.js:1:9`

    expect(parseStack(stack)).toEqual([
      { function: 'applyRemote', filename: 'https://x.example.com/assets/a.js', lineno: 1, colno: 48213 },
      { filename: 'https://x.example.com/assets/a.js', lineno: 1, colno: 9 },
    ])
  })
})

describe('envelope', () => {
  it('항목 길이는 한글을 포함한 본문의 UTF-8 바이트 수다', () => {
    const bytes = envelope(
      [{ event_id: 'e', timestamp: '2026-11-24T10:00:00.000Z', level: 'error', exceptions: [{ type: 'Error', value: '한글 메시지', frames: [] }] }],
      [{ reason: 'queue_overflow', category: 'event', quantity: 3 }],
      new Date('2026-11-24T10:00:01Z'),
    )
    const lines = Buffer.from(bytes).toString('utf8').trimEnd().split('\n')

    expect(JSON.parse(lines[0] ?? '')).toEqual({ sdk: 'vue/0.1.0', sent_at: '2026-11-24T10:00:01.000Z' })
    expect(JSON.parse(lines[1] ?? '').length).toBe(Buffer.byteLength(lines[2] ?? '', 'utf8'))
    expect(JSON.parse(lines[3] ?? '').type).toBe('client_report')
  })
})

describe('Client', () => {
  it('잡은 에러를 압축해 enc=gzip 으로 보낸다', async () => {
    const transport = new FakeTransport()
    const client = new Client(URL, { release: 'web@1', environment: 'production' }, transport, clock().now)

    client.capture(new TypeError("Cannot read properties of undefined (reading 'ops')"), { component: 'Editor' })
    await client.flush()

    expect(transport.sent[0]?.url).toBe(`${URL}&enc=gzip`)
    const event = transport.events()[0] as Record<string, unknown>
    expect(event.release).toBe('web@1')
    expect(event.tags).toEqual({ component: 'Editor' })
    expect((event.exceptions as { type: string }[])[0]?.type).toBe('TypeError')
    expect(event.event_id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/)
  })

  it('큐가 차면 버리고 센 건수를 다음 봉투의 client_report 로 보낸다', async () => {
    const transport = new FakeTransport()
    const client = new Client(URL, { queueSize: 10, foldThreshold: 1000 }, transport, clock().now)

    for (let i = 0; i < 25; i++) client.capture(new Error(`에러 ${i}`))
    await client.flush()

    expect(transport.events()).toHaveLength(10)
    expect(transport.discarded('queue_overflow')).toBe(15)
  })

  it('429 를 받으면 Retry-After 동안 보내지 않고 그동안의 건수를 센다', async () => {
    const transport = new FakeTransport()
    transport.replies.push({ status: 429, retryAfter: 30 })
    const c = clock()
    const client = new Client(URL, { foldThreshold: 1000 }, transport, c.now)

    client.capture(new Error('a'))
    await client.flush()
    c.t += 10_000
    client.capture(new Error('b'))
    await client.flush()
    expect(transport.sent).toHaveLength(1)

    c.t += 25_000
    client.capture(new Error('c'))
    await client.flush()

    expect(transport.sent).toHaveLength(2)
    expect(transport.discarded('rate_limited')).toBe(2)
  })

  it('401 을 받으면 전송을 멈춘다', async () => {
    const transport = new FakeTransport()
    transport.replies.push({ status: 401 })
    const client = new Client(URL, {}, transport, clock().now)
    const warn = console.warn
    console.warn = () => {}

    client.capture(new Error('a'))
    await client.flush()
    client.capture(new Error('b'))
    await client.flush()
    console.warn = warn

    expect(transport.sent).toHaveLength(1)
  })

  it('탭이 숨겨질 때는 압축하지 않고 sendBeacon 으로 보낸다', () => {
    const transport = new FakeTransport()
    const client = new Client(URL, {}, transport, clock().now)

    client.capture(new Error('떠나기 직전'))
    client.flushBeacon()

    expect(transport.beacons).toHaveLength(1)
    expect(transport.beacons[0]?.url).toBe(URL)
    expect(client.pending()).toBe(0)
  })

  it('같은 에러가 구간 안에 5건을 넘으면 대표 한 건에 횟수를 싣는다', async () => {
    const transport = new FakeTransport()
    const c = clock()
    const client = new Client(URL, { foldWindowMs: 10_000, foldThreshold: 5 }, transport, c.now)
    const same = () => new Error('같은 에러')

    for (let i = 0; i < 20; i++) client.capture(same())
    await client.flush()
    c.t += 10_000
    await client.flush()

    const counts = transport.events().map((e) => (e.count as number | undefined) ?? 1)
    expect(counts).toEqual([1, 1, 1, 1, 1, 15])
  })

  it('같은 에러 객체는 한 번만 보내고 에러가 아닌 값도 받는다', async () => {
    const transport = new FakeTransport()
    const client = new Client(URL, {}, transport, clock().now)
    const error = new Error('한 번')

    client.capture(error)
    client.capture(error)
    client.capture({ reason: '객체로 거절' })
    client.capture('문자열')
    await client.flush()

    expect(transport.events()).toHaveLength(3)
  })

  it('빵부스러기는 최근 것만 남긴다', async () => {
    const transport = new FakeTransport()
    const client = new Client(URL, { maxBreadcrumbs: 3 }, transport, clock().now)

    for (let i = 0; i < 5; i++) client.addBreadcrumb({ category: 'ui.click', message: `버튼 ${i}` })
    client.capture(new Error('a'))
    await client.flush()

    const crumbs = transport.events()[0]?.breadcrumbs as { message: string }[]
    expect(crumbs.map((b) => b.message)).toEqual(['버튼 2', '버튼 3', '버튼 4'])
  })
})
