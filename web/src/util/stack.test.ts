import { describe, expect, it } from 'vitest'
import { frameLabel, groupFrames } from './stack'

describe('groupFrames', () => {
  it('이어진 라이브러리 프레임은 하나로 접고 앱 프레임은 하나씩 둔다', () => {
    const groups = groupFrames([
      { module: 'com.zaxxer.hikari.HikariPool', function: 'getConnection', in_app: false },
      { module: 'com.app.PaymentService', function: 'charge', in_app: true },
      { module: 'org.springframework.A', function: 'a', in_app: false },
      { module: 'org.springframework.B', function: 'b', in_app: false },
      { module: 'com.app.Controller', function: 'pay', in_app: true },
    ])

    expect(groups.map((g) => (g.kind === 'app' ? 'app' : `lib${g.frames.length}`))).toEqual(['lib1', 'app', 'lib2', 'app'])
  })

  it('앱 프레임 판정이 하나도 없으면 모두 보인다', () => {
    const groups = groupFrames([{ function: 'applyRemote', filename: 'src/collab/client.ts' }, { function: 'b', filename: 'x.js' }])

    expect(groups.every((g) => g.kind === 'app')).toBe(true)
  })
})

describe('frameLabel', () => {
  it('자바와 브라우저 프레임을 한 줄로 쓴다', () => {
    expect(frameLabel({ module: 'com.app.Svc', function: 'run', filename: 'Svc.java', lineno: 42 })).toBe('com.app.Svc.run (Svc.java:42)')
    expect(frameLabel({ function: 'apply', filename: 'src/a.ts', lineno: 3, colno: 9 })).toBe('apply (src/a.ts:3:9)')
    expect(frameLabel({ function: 'x' })).toBe('x (알 수 없는 위치)')
    expect(frameLabel({ filename: 'src/collab.ts', lineno: 7, colno: 35 })).toBe('src/collab.ts:7:35')
  })
})
