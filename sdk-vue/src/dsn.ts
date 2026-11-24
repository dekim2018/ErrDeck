/**
 * DSN 은 `https://{공개키}@{호스트}/{프로젝트ID}` 다(5.2). 브라우저는 키를 헤더 대신 쿼리 `?key=` 로 보낸다.
 * 사용자 정의 헤더를 붙이면 브라우저가 사전 요청(OPTIONS)을 먼저 보내 요청 수가 두 배가 되기 때문이다.
 */
export function ingestUrl(dsn: string): string {
  const match = /^(https?):\/\/([A-Za-z0-9_-]+)@([^/?#]+)\/([1-9][0-9]{0,9})$/.exec(dsn.trim())
  if (!match) {
    throw new Error('ErrDeck: DSN 형식이 틀렸다')
  }
  const [, scheme, key, host, projectId] = match
  return `${scheme}://${host}/ingest/${projectId}/envelope?key=${key}`
}
