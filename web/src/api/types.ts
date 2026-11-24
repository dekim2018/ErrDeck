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
