import { describe, expect, it } from 'vitest'
import { compact, relative } from './format'

describe('relative', () => {
  const now = new Date('2026-11-24T12:00:00Z')

  it('지난 시간을 가장 큰 단위로 짧게 보인다', () => {
    expect(relative('2026-11-24T11:59:50Z', now)).toBe('방금')
    expect(relative('2026-11-24T11:57:00Z', now)).toBe('3분 전')
    expect(relative('2026-11-24T10:00:00Z', now)).toBe('2시간 전')
    expect(relative('2026-11-19T12:00:00Z', now)).toBe('5일 전')
  })
})

describe('compact', () => {
  it('큰 수를 천, 만, 억 단위로 줄인다', () => {
    expect(compact(999)).toBe('999')
    expect(compact(1234)).toBe('1.2천')
    expect(compact(10_000)).toBe('1만')
    expect(compact(1_234_567)).toBe('123.5만')
    expect(compact(250_000_000)).toBe('2.5억')
  })
})
