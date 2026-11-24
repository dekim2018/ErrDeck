/** 지금부터 얼마나 지났는지. 예: "방금", "3분 전", "2시간 전", "5일 전". */
export function relative(iso: string, now: Date = new Date()): string {
  const seconds = Math.max(0, Math.round((now.getTime() - new Date(iso).getTime()) / 1000))
  if (seconds < 45) return '방금'
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes}분 전`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours}시간 전`
  const days = Math.round(hours / 24)
  return `${days}일 전`
}

/** 큰 수를 짧게. 1234 는 "1.2천", 1234567 은 "123.5만". */
export function compact(n: number): string {
  if (n < 1000) return String(n)
  if (n < 10_000) return `${(n / 1000).toFixed(1).replace(/\.0$/, '')}천`
  if (n < 100_000_000) return `${(n / 10_000).toFixed(1).replace(/\.0$/, '')}만`
  return `${(n / 100_000_000).toFixed(1).replace(/\.0$/, '')}억`
}

export function dateTime(iso: string): string {
  const d = new Date(iso)
  const pad = (v: number) => String(v).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

export const STATUS_LABELS: Record<string, string> = {
  unresolved: '미해결',
  resolved: '해결',
  ignored: '무시',
  regressed: '재발',
}
