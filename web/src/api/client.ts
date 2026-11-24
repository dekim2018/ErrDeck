import type { IssueDetail, IssueStatus, IssueSummary, Period, Project, ReleaseSummary, Sort, SystemView, Token, User, EndpointTable, TransactionView, Overview, Monitor, Incident, AlertRule, NotificationView } from './types'

/** api 가 돌려준 실패. 본문은 RFC 9457 형식이다. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message)
  }
}

type Fetch = typeof fetch

/**
 * api 호출기. 액세스 토큰은 메모리에만 두고, 리프레시 토큰은 HttpOnly 쿠키라 화면 코드가 만지지 않는다(15.2).
 * 401 을 받으면 한 번만 재발급해서 다시 보낸다. 동시에 여러 요청이 401 을 받아도 재발급은 한 번만 한다.
 */
export class Client {
  private token: string | null = null
  private refreshing: Promise<Token | null> | null = null

  constructor(
    private readonly fetcher: Fetch = (...args) => fetch(...args),
    private readonly onSignedOut: () => void = () => {},
  ) {}

  setToken(token: string | null): void {
    this.token = token
  }

  hasToken(): boolean {
    return this.token !== null
  }

  async signup(email: string, password: string): Promise<User> {
    return this.request<User>('POST', '/api/auth/signup', { email, password }, false)
  }

  async login(email: string, password: string): Promise<Token> {
    const token = await this.request<Token>('POST', '/api/auth/login', { email, password }, false)
    this.token = token.accessToken
    return token
  }

  /** 쿠키로 새 토큰을 받는다. 없거나 만료되었으면 null. */
  async refresh(): Promise<Token | null> {
    if (!this.refreshing) {
      this.refreshing = this.request<Token>('POST', '/api/auth/refresh', undefined, false)
        .then((token) => {
          this.token = token.accessToken
          return token
        })
        .catch(() => {
          this.token = null
          return null
        })
        .finally(() => {
          this.refreshing = null
        })
    }
    return this.refreshing
  }

  async logout(): Promise<void> {
    await this.request<void>('POST', '/api/auth/logout', undefined, false).catch(() => undefined)
    this.token = null
  }

  me(): Promise<{ user: User; systems: SystemView[] }> {
    return this.request('GET', '/api/me')
  }

  projects(systemId: number): Promise<Project[]> {
    return this.request('GET', `/api/systems/${systemId}/projects`)
  }

  issues(projectId: number, query: { status?: string; release?: string; period: Period; sort: Sort; page?: number }): Promise<IssueSummary[]> {
    const params = new URLSearchParams()
    if (query.status) params.set('status', query.status)
    if (query.release) params.set('release', query.release)
    params.set('period', query.period)
    params.set('sort', query.sort)
    params.set('page', String(query.page ?? 0))
    return this.request('GET', `/api/projects/${projectId}/issues?${params}`)
  }

  issue(issueId: number, period: Period): Promise<IssueDetail> {
    return this.request('GET', `/api/issues/${issueId}?period=${period}`)
  }

  changeStatus(issueId: number, status: IssueStatus, extra: { resolvedInRelease?: string; ignoredUntil?: string } = {}): Promise<void> {
    return this.request('PATCH', `/api/issues/${issueId}`, { status, ...extra })
  }

  endpoints(projectId: number, period: string): Promise<EndpointTable> {
    return this.request('GET', `/api/projects/${projectId}/performance/endpoints?period=${period}`)
  }

  slowTransactions(projectId: number, name: string | null, period: string): Promise<TransactionView[]> {
    const params = new URLSearchParams({ period })
    if (name) params.set('name', name)
    return this.request('GET', `/api/projects/${projectId}/performance/transactions?${params}`)
  }

  trace(traceId: string): Promise<TransactionView[]> {
    return this.request('GET', `/api/traces/${traceId}`)
  }

  overview(): Promise<Overview> {
    return this.request('GET', '/api/overview')
  }

  monitors(projectId: number): Promise<Monitor[]> {
    return this.request('GET', `/api/projects/${projectId}/monitors`)
  }

  createMonitor(projectId: number, body: Partial<Monitor>): Promise<{ id: number }> {
    return this.request('POST', `/api/projects/${projectId}/monitors`, body)
  }

  pauseMonitor(monitorId: number, paused: boolean): Promise<void> {
    return this.request('PATCH', `/api/monitors/${monitorId}`, { paused })
  }

  deleteMonitor(monitorId: number): Promise<void> {
    return this.request('DELETE', `/api/monitors/${monitorId}`)
  }

  incidents(monitorId: number): Promise<Incident[]> {
    return this.request('GET', `/api/monitors/${monitorId}/incidents`)
  }

  alertRules(projectId: number): Promise<AlertRule[]> {
    return this.request('GET', `/api/projects/${projectId}/alert-rules`)
  }

  createAlertRule(projectId: number, body: Partial<AlertRule>): Promise<{ id: number }> {
    return this.request('POST', `/api/projects/${projectId}/alert-rules`, body)
  }

  toggleAlertRule(ruleId: number, enabled: boolean): Promise<void> {
    return this.request('PATCH', `/api/alert-rules/${ruleId}`, { enabled })
  }

  notifications(projectId: number): Promise<NotificationView[]> {
    return this.request('GET', `/api/projects/${projectId}/notifications`)
  }

  releases(projectId: number): Promise<ReleaseSummary[]> {
    return this.request('GET', `/api/projects/${projectId}/releases`)
  }

  comment(issueId: number, body: string): Promise<{ id: number }> {
    return this.request('POST', `/api/issues/${issueId}/comments`, { body })
  }

  private async request<T>(method: string, path: string, body?: unknown, authorized = true): Promise<T> {
    let response = await this.send(method, path, body, authorized)
    if (response.status === 401 && authorized) {
      const renewed = await this.refresh()
      if (!renewed) {
        this.onSignedOut()
        throw new ApiError(401, '다시 로그인해야 한다')
      }
      response = await this.send(method, path, body, authorized)
    }
    if (!response.ok) {
      let detail = response.statusText
      try {
        const problem = (await response.json()) as { detail?: string }
        detail = problem.detail ?? detail
      } catch {
        // 본문이 JSON 이 아니면 상태 문구를 쓴다.
      }
      throw new ApiError(response.status, detail)
    }
    if (response.status === 204 || response.headers.get('Content-Length') === '0') {
      return undefined as T
    }
    const text = await response.text()
    return (text ? JSON.parse(text) : undefined) as T
  }

  private send(method: string, path: string, body: unknown, authorized: boolean): Promise<Response> {
    const headers: Record<string, string> = {}
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    if (authorized && this.token) headers.Authorization = `Bearer ${this.token}`
    return this.fetcher(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: 'same-origin',
    })
  }
}
