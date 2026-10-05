import { expect, it } from 'vitest'
import { utcMonth } from './usage'

it('matches server UTC month across local timezone and year boundaries', () => {
  expect(utcMonth(new Date('2026-01-01T08:00:00+09:00'))).toBe('2025-12')
  expect(utcMonth(new Date('2026-09-30T17:00:00-07:00'))).toBe('2026-10')
})
