/** core 의 스택 프레임과 같은 모양. 브라우저 프레임은 module 이 없다. */
export interface Frame {
  function?: string
  filename?: string
  lineno?: number
  colno?: number
}

// V8(크롬, 엣지): "    at fn (https://x/a.js:1:2)" 또는 "    at https://x/a.js:1:2", "    at async fn (...)"
const V8 = /^\s*at (?:(?:async )?(.*?) \()?(.*?):(\d+):(\d+)\)?\s*$/
// 파이어폭스, 사파리: "fn@https://x/a.js:1:2" 또는 "@https://x/a.js:1:2"
const GECKO = /^\s*(.*?)@(.*?):(\d+):(\d+)\s*$/

/**
 * Error.stack 문자열을 프레임으로 바꾼다. 예외가 난 곳이 먼저 온다(6장 끝). 읽을 수 없는 줄은 건너뛴다.
 * 앱 프레임 판정은 하지 않는다. 소스맵으로 복원한 뒤 worker 가 node_modules 밖인지로 정한다(6.3).
 */
export function parseStack(stack: string | undefined, max = 50): Frame[] {
  if (!stack) return []
  const frames: Frame[] = []
  for (const line of stack.split('\n')) {
    const m = V8.exec(line) ?? GECKO.exec(line)
    if (!m || !m[2] || m[2] === 'native') continue
    const frame: Frame = { filename: m[2], lineno: Number(m[3]), colno: Number(m[4]) }
    const fn = m[1]?.trim()
    if (fn) frame.function = fn
    frames.push(frame)
    if (frames.length >= max) break
  }
  return frames
}
