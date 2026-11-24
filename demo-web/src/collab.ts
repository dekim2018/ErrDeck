export interface Change {
  ops?: string[]
}

/** CoPage 의 클라이언트 OT 오류를 흉내 낸다(12.5). ops 가 없으면 TypeError 가 난다. */
export function applyRemote(change: Change): number {
  return (change.ops as string[]).length
}
