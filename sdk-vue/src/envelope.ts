import type { ErrDeckEvent } from './types'

export const SDK = 'vue/0.1.0'

export interface Discarded {
  reason: string
  category: string
  quantity: number
}

const encoder = new TextEncoder()

/**
 * 줄 단위 JSON 봉투(5.3). 항목 머리 줄의 length 는 본문의 UTF-8 바이트 수다. 한글 메시지가 있어도 core 가 같은 경계로 자른다.
 */
export function envelope(events: ErrDeckEvent[], discarded: Discarded[], now: Date = new Date()): Uint8Array {
  const parts: string[] = [JSON.stringify({ sdk: SDK, sent_at: now.toISOString() })]
  const add = (type: string, body: string) => {
    parts.push(JSON.stringify({ type, length: encoder.encode(body).length }), body)
  }
  for (const event of events) add('event', JSON.stringify(event))
  if (discarded.length) add('client_report', JSON.stringify({ timestamp: now.toISOString(), discarded }))
  return encoder.encode(parts.join('\n') + '\n')
}

/** 브라우저의 CompressionStream 으로 gzip 한다. 없으면 null. */
export async function gzip(data: Uint8Array): Promise<Uint8Array | null> {
  if (typeof CompressionStream === 'undefined') return null
  const stream = new Blob([data as BlobPart]).stream().pipeThrough(new CompressionStream('gzip'))
  return new Uint8Array(await new Response(stream).arrayBuffer())
}
