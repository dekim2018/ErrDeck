import type { ErrDeckEvent } from './types'

interface Window {
  start: number
  passed: number
  folded: number
  sample?: ErrDeckEvent
}

/**
 * 같은 에러를 접는다(11.1). 같은 스택의 에러가 구간 안에 기준을 넘으면 이후는 횟수만 세고, 구간이 끝날 때 대표 한 건에 횟수를 싣는다.
 * 사용자가 버튼을 연타하거나 반복 렌더링에서 같은 에러가 쏟아질 때 네트워크를 지킨다. 기억하는 스택 종류에 상한을 둔다.
 */
export class Folder {
  private readonly windows = new Map<string, Window>()

  constructor(
    private readonly windowMs: number,
    private readonly threshold: number,
    private readonly maxSignatures = 200,
  ) {}

  /** 그대로 보내면 true. */
  admit(event: ErrDeckEvent, now: number): boolean {
    const key = signature(event)
    let w = this.windows.get(key)
    if (!w || now - w.start >= this.windowMs) {
      if (!w && this.windows.size >= this.maxSignatures) return true
      if (w && w.folded > 0) this.retired.push(w)
      w = { start: now, passed: 0, folded: 0 }
      this.windows.set(key, w)
    }
    if (w.passed < this.threshold) {
      w.passed++
      return true
    }
    if (w.folded === 0) w.sample = event
    w.folded++
    return false
  }

  private retired: Window[] = []

  /** 구간이 끝난 것의 대표 건. */
  drain(now: number): ErrDeckEvent[] {
    const out: ErrDeckEvent[] = []
    const take = (w: Window) => {
      if (w.folded > 0 && w.sample) out.push({ ...w.sample, event_id: uuid(), count: w.folded })
    }
    for (const [key, w] of this.windows) {
      if (now - w.start >= this.windowMs) {
        take(w)
        this.windows.delete(key)
      }
    }
    this.retired.forEach(take)
    this.retired = []
    return out
  }
}

function signature(event: ErrDeckEvent): string {
  const ex = event.exceptions[0]
  if (!ex) return `m:${event.message ?? ''}`
  return `${ex.type}|${ex.frames
    .slice(0, 10)
    .map((f) => `${f.filename}:${f.lineno}:${f.colno}`)
    .join(',')}`
}

export function uuid(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID()
  // 오래된 브라우저. 보안 용도가 아니라 중복 확인용 ID 다.
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16)
  })
}
