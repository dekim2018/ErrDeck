export interface Reply {
  status: number
  /** Retry-After 헤더(초). ingest 가 Access-Control-Expose-Headers 로 열어 둔다(5장 끝) */
  retryAfter?: number
}

export interface Transport {
  send(url: string, body: Uint8Array): Promise<Reply>
  /** 탭이 숨겨질 때. 페이지가 사라져도 끝까지 간다. 받아들였으면 true */
  beacon(url: string, body: Uint8Array): boolean
}

/**
 * 단순 요청(simple request)만 쓴다. 본문 형식은 text/plain, 키는 쿼리, 사용자 정의 헤더 없음. 그래야 브라우저가 사전 요청을
 * 보내지 않는다(5.2). keepalive 는 본문이 64KB 이하일 때만 브라우저가 받아 준다.
 */
export const browserTransport: Transport = {
  async send(url, body) {
    const response = await fetch(url, {
      method: 'POST',
      body: body as BodyInit,
      headers: { 'Content-Type': 'text/plain' },
      keepalive: body.length < 60_000,
      credentials: 'omit',
      mode: 'cors',
    })
    const retry = Number(response.headers.get('Retry-After'))
    return { status: response.status, retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined }
  },
  beacon(url, body) {
    return typeof navigator !== 'undefined' && typeof navigator.sendBeacon === 'function'
      ? navigator.sendBeacon(url, new Blob([body as BlobPart], { type: 'text/plain' }))
      : false
  },
}
