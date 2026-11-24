import { uuid } from './fold'

/** core 의 트랜잭션 JSON(8.2)과 같은 이름을 쓴다. */
export interface ErrDeckSpan {
  span_id: string
  parent_span_id?: string
  op: string
  description: string
  start_offset_ms: number
  duration_ms: number
  repeat?: number
}

export interface ErrDeckTransaction {
  trace_id: string
  span_id: string
  name: string
  op: string
  start_timestamp: string
  duration_ms: number
  status: string
  environment?: string
  release?: string
  partial?: boolean
  spans: ErrDeckSpan[]
}

export interface TracingOptions {
  /** 시작할 때 뽑는 비율. 기본 0.1(8.4) */
  tracesSampleRate?: number
  /** 뽑히지 않았어도 이보다 느리면 남긴다. 기본 1000ms */
  slowThresholdMs?: number
  /**
   * traceparent 를 붙일 주소. 기본은 같은 출처만이다. 다른 출처에 붙이면 브라우저가 사전 요청을 보내고, 그쪽 서버가
   * 헤더를 허용해야 한다.
   */
  tracePropagationTargets?: (string | RegExp)[]
}

const MAX_SPANS = 500
const IDLE_MS = 500
const MAX_MS = 15_000

interface Active {
  traceId: string
  spanId: string
  sampled: boolean
  name: string
  op: string
  start: number
  spans: ErrDeckSpan[]
  pending: number
  error: boolean
  timer?: ReturnType<typeof setTimeout>
}

function hex(n: number): string {
  return uuid().replace(/-/g, '').slice(0, n)
}

/**
 * 페이지 이동 하나를 트랜잭션으로, 그 안의 API 호출을 스팬으로 남긴다(8.3). 호출이 모두 끝나고 잠시 조용하면 끝낸다.
 * 서버로 가는 호출에 traceparent 를 붙여 서버의 트랜잭션과 같은 trace_id 로 잇는다.
 */
export class Tracer {
  private active: Active | null = null
  private done: ErrDeckTransaction[] = []

  constructor(
    private readonly options: TracingOptions & { environment?: string; release?: string },
    private readonly now: () => number = () => Date.now(),
  ) {}

  traceId(): string | undefined {
    return this.active?.traceId
  }

  start(name: string, op: string): void {
    this.end()
    const rate = this.options.tracesSampleRate ?? 0.1
    this.active = { traceId: hex(32), spanId: hex(16), sampled: Math.random() < rate, name, op, start: this.now(), spans: [], pending: 0, error: false }
    this.schedule()
  }

  rename(name: string): void {
    if (this.active && name) this.active.name = name
  }

  markError(): void {
    if (this.active) this.active.error = true
  }

  /** API 호출 하나를 연다. 붙일 traceparent 와 닫는 함수를 돌려준다. */
  startSpan(op: string, description: string, url: string): { header?: string; finish: (status?: number) => void } {
    const tx = this.active
    if (!tx) return { finish: () => {} }
    tx.pending++
    const spanId = hex(16)
    const begin = this.now()
    const header = this.propagates(url) ? `00-${tx.traceId}-${spanId}-${tx.sampled ? '01' : '00'}` : undefined
    return {
      header,
      finish: (status) => {
        tx.pending--
        if (status === undefined || status >= 500) tx.error = true
        this.add(tx, { span_id: spanId, parent_span_id: tx.spanId, op, description, start_offset_ms: begin - tx.start, duration_ms: this.now() - begin })
        if (tx === this.active) this.schedule()
      },
    }
  }

  /** 끝난 것을 꺼낸다. 전송은 Client 가 한다. */
  take(): ErrDeckTransaction[] {
    const out = this.done
    this.done = []
    return out
  }

  /** 진행 중인 것을 끝낸다. 탭을 떠날 때 부른다. */
  end(): void {
    const tx = this.active
    if (!tx) return
    clearTimeout(tx.timer)
    this.active = null
    const duration = this.now() - tx.start
    const slow = duration >= (this.options.slowThresholdMs ?? 1000)
    if (!tx.sampled && !tx.error && !slow) return
    const out: ErrDeckTransaction = {
      trace_id: tx.traceId,
      span_id: tx.spanId,
      name: tx.name,
      op: tx.op,
      start_timestamp: new Date(tx.start).toISOString(),
      duration_ms: duration,
      status: tx.error ? 'error' : 'ok',
      spans: tx.spans,
    }
    if (!tx.sampled) out.partial = true
    if (this.options.environment) out.environment = this.options.environment
    if (this.options.release) out.release = this.options.release
    if (this.done.length < 100) this.done.push(out)
  }

  /** 같은 호출이 연속으로 반복되면 하나로 접고 횟수를 센다(8.5). */
  private add(tx: Active, span: ErrDeckSpan) {
    const last = tx.spans[tx.spans.length - 1]
    if (last && last.op === span.op && last.description === span.description) {
      last.repeat = (last.repeat ?? 1) + 1
      last.duration_ms += span.duration_ms
    } else if (tx.spans.length < MAX_SPANS) {
      tx.spans.push(span)
    }
  }

  private schedule() {
    const tx = this.active
    if (!tx) return
    clearTimeout(tx.timer)
    const left = MAX_MS - (this.now() - tx.start)
    tx.timer = setTimeout(() => (tx.pending > 0 && left > IDLE_MS ? this.schedule() : this.end()), Math.max(0, Math.min(IDLE_MS, left)))
  }

  private propagates(url: string): boolean {
    const targets = this.options.tracePropagationTargets
    if (!targets) return typeof location === 'undefined' || url.startsWith('/') || url.startsWith(location.origin)
    return targets.some((t) => (typeof t === 'string' ? url.startsWith(t) : t.test(url)))
  }
}
