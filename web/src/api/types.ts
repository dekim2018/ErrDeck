// api 가 돌려주는 모양. 서버의 record 와 이름을 맞춘다.

export type Role = 'VIEWER' | 'MEMBER' | 'ADMIN' | 'OWNER'
export type IssueStatus = 'unresolved' | 'resolved' | 'ignored' | 'regressed'
export type Period = '1h' | '24h' | '7d' | '14d' | '30d'
export type Sort = 'recent' | 'frequent' | 'users'

export interface User {
  id: number
  email: string
  admin: boolean
}

export interface Token {
  accessToken: string
  expiresAt: string
  user: User
}

export interface SystemView {
  id: number
  name: string
  role: Role
}

export interface Project {
  id: number
  systemId: number
  slug: string
  platform: 'SPRING' | 'VUE'
  rateLimitPerMin: number
  allowedOrigins: string[]
}

export interface IssueSummary {
  id: number
  status: IssueStatus
  title: string
  culprit: string | null
  firstSeen: string
  lastSeen: string
  lastRelease: string | null
  count: number
  users: number
}

export interface Bucket {
  start: string
  times: number
}

export interface Comment {
  id: number
  author: string
  body: string
  createdAt: string
}

export interface StackFrame {
  module?: string
  function?: string
  filename?: string
  lineno?: number
  colno?: number
  in_app?: boolean
  /** 복원하기 전의 번들 주소 */
  abs_path?: string
  /** 원본 코드의 앞뒤 줄. 맨 위 앱 프레임에만 있다 */
  context?: string[]
  context_start?: number
}

export interface ExceptionInfo {
  type: string
  value?: string
  frames?: StackFrame[]
}

export interface Breadcrumb {
  timestamp: string
  category?: string
  message?: string
  data?: Record<string, string>
}

/** 원본 이벤트. core 의 이벤트 JSON 그대로다. */
export interface EventPayload {
  event_id: string
  timestamp?: string
  level?: string
  message?: string
  environment?: string
  release?: string
  user_id?: string
  trace_id?: string
  count?: number
  exceptions?: ExceptionInfo[]
  request?: { method?: string; url?: string; query_string?: string; headers?: Record<string, string> }
  tags?: Record<string, string>
  breadcrumbs?: Breadcrumb[]
}

export interface IssueDetail {
  issue: IssueSummary
  resolvedInRelease: string | null
  ignoredUntil: string | null
  fingerprints: string[]
  trend: Bucket[]
  latestEvent: EventPayload | null
  latestEventAt: string | null
  latestEnvironment: string | null
  traceId: string | null
  comments: Comment[]
  grafanaUrl: string
}

export interface ReleaseSummary {
  version: string
  createdAt: string
  artifacts: number
  newIssues: number
  regressed: number
}

/** 성능 화면(12.3). source 가 samples 면 프로메테우스를 못 읽어 표본 트랜잭션으로 계산한 값이다. */
export interface Endpoint {
  name: string
  requests: number
  p95Ms: number
  errorRate: number
}

export interface EndpointTable {
  source: 'PROMETHEUS' | 'SAMPLES'
  endpoints: Endpoint[]
}

export interface SpanView {
  spanId: string
  parentSpanId: string | null
  op: string
  description: string
  startOffsetMs: number
  durationMs: number
  repeat: number
}

export interface TransactionView {
  projectId: number
  traceId: string
  spanId: string
  parentSpanId: string | null
  name: string
  op: string
  timestamp: string
  durationMs: number
  status: string
  partial: boolean
  release: string
  spans: SpanView[]
}

export type Signal = 'OK' | 'WARN' | 'DOWN'

/** 요약 카드(12.2). 프로메테우스에 없으면 null. */
export interface Card {
  up: number | null
  cpu: number | null
  heap: number | null
  p95Ms: number | null
  errorRate: number | null
  poolPending: number | null
}

export interface ProjectOverview {
  id: number
  slug: string
  platform: string
  signal: Signal
  openIssues: number
  regressed: number
  newIssues24h: number
  monitorsDown: number
  monitors: number
  card: Card | null
  reasons: string[]
}

export interface SystemOverview {
  id: number
  name: string
  role: string
  signal: Signal
  projects: ProjectOverview[]
}

export interface Overview {
  systems: SystemOverview[]
  metricsAvailable: boolean
  grafanaUrl: string | null
}

export interface Monitor {
  id: number
  projectId: number
  name: string | null
  url: string
  method: string
  intervalSeconds: number
  timeoutMs: number
  expectedStatusMin: number
  expectedStatusMax: number
  expectedBody: string | null
  status: 'unknown' | 'up' | 'down'
  pausedUntil: string | null
  lastCheckedAt: string | null
  lastError: string | null
  certExpiresAt: string | null
  uptime24h: number | null
  uptime30d: number | null
  openIncidentSince: string | null
}

export interface Incident {
  id: number
  startedAt: string
  endedAt: string | null
  cause: string | null
  grafanaUrl: string | null
}

export interface AlertRule {
  id: number
  projectId: number
  kind: string
  threshold: number | null
  windowMinutes: number | null
  channel: string
  target: string
  cooldownMinutes: number
  enabled: boolean
}

export interface NotificationView {
  id: number
  ruleId: number
  kind: string
  title: string
  status: 'pending' | 'sent' | 'failed'
  attempts: number
  lastError: string | null
  createdAt: string
  sentAt: string | null
}
