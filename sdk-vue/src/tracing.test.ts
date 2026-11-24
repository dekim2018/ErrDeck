import { afterEach, describe, expect, it, vi } from 'vitest'
import { Client } from './client'
import { Tracer } from './tracing'
import type { Transport } from './transport'

describe('Tracer', () => {
  afterEach(() => vi.useRealTimers())

  it('API 호출을 스팬으로 남기고 traceparent 를 만든다', () => {
    let t = 1000
    const tracer = new Tracer({ tracesSampleRate: 1, tracePropagationTargets: ['/api'] }, () => t)
    tracer.start('/matches/:id', 'navigation')
    const span = tracer.startSpan('http.client', 'GET /api/matches/1', '/api/matches/1')
    expect(span.header).toMatch(/^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/)
    expect(span.header).toContain(tracer.traceId())
    t = 1050
    span.finish(200)
    t = 1100
    tracer.end()

    const [tx] = tracer.take()
    expect(tx?.name).toBe('/matches/:id')
    expect(tx?.duration_ms).toBe(100)
    expect(tx?.spans).toEqual([expect.objectContaining({ op: 'http.client', start_offset_ms: 0, duration_ms: 50 })])
  })

  it('정한 주소가 아니면 traceparent 를 붙이지 않는다', () => {
    const tracer = new Tracer({ tracesSampleRate: 1, tracePropagationTargets: ['/api'] })
    tracer.start('/', 'pageload')
    expect(tracer.startSpan('http.client', 'GET https://cdn.example.com/x', 'https://cdn.example.com/x').header).toBeUndefined()
  })

  it('같은 호출이 반복되면 접는다', () => {
    const tracer = new Tracer({ tracesSampleRate: 1 })
    tracer.start('/', 'pageload')
    for (let i = 0; i < 12; i++) tracer.startSpan('http.client', 'GET /api/items', '/api/items').finish(200)
    tracer.end()
    expect(tracer.take()[0]?.spans).toEqual([expect.objectContaining({ repeat: 12 })])
  })

  it('뽑히지 않았으면 에러나 느릴 때만 부분 추적으로 남긴다', () => {
    let t = 0
    const tracer = new Tracer({ tracesSampleRate: 0, slowThresholdMs: 1000 }, () => t)
    tracer.start('/a', 'navigation')
    tracer.end()
    tracer.start('/b', 'navigation')
    tracer.startSpan('http.client', 'GET /api/x', '/api/x').finish(500)
    tracer.end()
    tracer.start('/c', 'navigation')
    t = 2000
    tracer.end()

    const txs = tracer.take()
    expect(txs.map((x) => x.name)).toEqual(['/b', '/c'])
    expect(txs.every((x) => x.partial)).toBe(true)
  })

  it('호출이 끝나고 조용해지면 스스로 끝낸다', () => {
    vi.useFakeTimers()
    const tracer = new Tracer({ tracesSampleRate: 1 })
    tracer.start('/', 'pageload')
    const span = tracer.startSpan('http.client', 'GET /api/x', '/api/x')
    vi.advanceTimersByTime(2000)
    expect(tracer.take()).toHaveLength(0)
    span.finish(200)
    vi.advanceTimersByTime(600)
    expect(tracer.take()).toHaveLength(1)
  })
})

describe('Client 와 추적', () => {
  it('에러 이벤트에 진행 중인 trace_id 를 넣고 트랜잭션은 kind=tx 로 보낸다', async () => {
    const sent: string[] = []
    const transport: Transport = {
      send: async (url) => {
        sent.push(url)
        return { status: 202 }
      },
      beacon: () => true,
    }
    const client = new Client('https://ingest.example.com/ingest/1/envelope?key=k', { tracesSampleRate: 1 }, transport)
    client.tracer?.start('/', 'pageload')
    const traceId = client.tracer?.traceId()
    client.capture(new Error('터졌다'))
    client.tracer?.end()
    await client.flush()

    expect(sent.some((u) => u.includes('&kind=tx'))).toBe(true)
    expect(sent.some((u) => !u.includes('kind=tx'))).toBe(true)
    expect(traceId).toMatch(/^[0-9a-f]{32}$/)
  })
})
