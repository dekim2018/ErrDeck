import type { Frame } from './stack'

export interface Breadcrumb {
  timestamp: string
  category: string
  message?: string
  data?: Record<string, string>
}

/** core 의 이벤트 JSON(02 작업, 5장 끝)과 같은 이름을 쓴다. */
export interface ErrDeckEvent {
  event_id: string
  timestamp: string
  level: 'error'
  message?: string
  environment?: string
  release?: string
  count?: number
  exceptions: { type: string; value?: string; frames: Frame[] }[]
  request?: { method: string; url: string; headers: Record<string, string> }
  tags?: Record<string, string>
  breadcrumbs?: Breadcrumb[]
}

export interface Options {
  /** `https://{공개키}@{호스트}/{프로젝트ID}`. 비어 있으면 아무것도 하지 않는다 */
  dsn?: string
  release?: string
  environment?: string
  /** vue-router. 있으면 라우트 이동을 빵부스러기로 남긴다 */
  router?: { afterEach(guard: (to: { fullPath: string }, from: { fullPath: string }) => void): unknown }
  /** 빵부스러기를 남길지. 기본 true */
  breadcrumbs?: boolean
  maxBreadcrumbs?: number
  /** 보내기를 기다리는 이벤트 상한. 넘으면 버리고 센다(11.1) */
  queueSize?: number
  flushIntervalMs?: number
  /** 같은 에러를 세는 구간과 기준(11.1) */
  foldWindowMs?: number
  foldThreshold?: number
}
