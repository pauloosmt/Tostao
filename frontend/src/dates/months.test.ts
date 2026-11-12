import { describe, expect, it } from 'vitest'
import { currentMonth, monthLabel, monthWord, shiftMonth } from './months'

describe('meses', () => {
  it('o mês de hoje e o rótulo por extenso', () => {
    expect(currentMonth('2026-11-12')).toBe('2026-11')
    expect(monthLabel('2026-11')).toBe('Novembro de 2026')
    expect(monthWord('2026-03')).toBe('março')
  })

  it('anda para trás e para frente, virando o ano', () => {
    expect(shiftMonth('2026-01', -1)).toBe('2025-12')
    expect(shiftMonth('2026-12', 1)).toBe('2027-01')
    expect(shiftMonth('2026-11', 0)).toBe('2026-11')
  })

  it('não sai de 2000-01 a 2100-12', () => {
    expect(shiftMonth('2000-01', -1)).toBeNull()
    expect(shiftMonth('2100-12', 1)).toBeNull()
    expect(shiftMonth('2000-01', 1)).toBe('2000-02')
  })
})
