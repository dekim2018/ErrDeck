import type { StackFrame } from '../api/types'

/** 화면에 보일 프레임 묶음. 앱 프레임은 하나씩, 이어진 라이브러리 프레임은 접어서 개수만 보인다. */
export type FrameGroup = { kind: 'app'; frame: StackFrame } | { kind: 'library'; frames: StackFrame[] }

/**
 * 프레임을 화면 순서로 묶는다. 스택은 예외가 난 곳이 먼저다(6장 끝). 라이브러리 프레임이 이어지면 하나로 접어
 * 내 코드가 어디를 지났는지 먼저 보이게 한다. 앱 프레임 판정이 없으면(in_app 이 비어 있음) 모두 앱 프레임으로 본다.
 */
export function groupFrames(frames: StackFrame[]): FrameGroup[] {
  const groups: FrameGroup[] = []
  const anyJudged = frames.some((f) => f.in_app !== undefined)
  for (const frame of frames) {
    const isApp = !anyJudged || frame.in_app === true
    const last = groups[groups.length - 1]
    if (isApp) {
      groups.push({ kind: 'app', frame })
    } else if (last && last.kind === 'library') {
      last.frames.push(frame)
    } else {
      groups.push({ kind: 'library', frames: [frame] })
    }
  }
  return groups
}

/** 한 줄 표기. 자바는 클래스.메서드(파일:줄), 브라우저는 함수(파일:줄:열). */
export function frameLabel(frame: StackFrame): string {
  const where = frame.filename
    ? `${frame.filename}${frame.lineno ? `:${frame.lineno}` : ''}${frame.colno ? `:${frame.colno}` : ''}`
    : '알 수 없는 위치'
  const fn = frame.module ? `${frame.module}.${frame.function ?? '?'}` : (frame.function ?? '?')
  return `${fn} (${where})`
}
