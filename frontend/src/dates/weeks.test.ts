import { describe, expect, it } from 'vitest'
import { currentWeek, shiftWeek, weekLabel, weekOf, weekRange } from './weeks'

describe('semanas ISO', () => {
  it('a semana de uma data: o ano é o da quinta-feira (virada de ano)', () => {
    expect(weekOf('2026-11-12')).toBe('2026-W46')
    expect(weekOf('2026-01-01')).toBe('2026-W01')
    expect(weekOf('2024-12-30')).toBe('2025-W01') // segunda de dezembro que já é da semana 1 do ano seguinte
    expect(weekOf('2021-01-03')).toBe('2020-W53') // domingo de janeiro que ainda é do ano anterior (53 semanas)
    expect(weekOf('2026-11-09')).toBe('2026-W46') // segunda
    expect(weekOf('2026-11-15')).toBe('2026-W46') // domingo
    expect(currentWeek('2026-11-12')).toBe('2026-W46')
  })

  it('de segunda a domingo, inclusive atravessando o ano', () => {
    expect(weekRange('2026-W46')).toEqual({ start: '2026-11-09', end: '2026-11-15' })
    expect(weekRange('2025-W01')).toEqual({ start: '2024-12-30', end: '2025-01-05' })
    expect(weekRange('2020-W53')).toEqual({ start: '2020-12-28', end: '2021-01-03' })
  })

  it('anda para trás e para frente, virando o ano (com a semana 53)', () => {
    expect(shiftWeek('2025-W01', -1)).toBe('2024-W52')
    expect(shiftWeek('2020-W53', 1)).toBe('2021-W01')
    expect(shiftWeek('2020-W52', 1)).toBe('2020-W53')
    expect(shiftWeek('2026-W46', 0)).toBe('2026-W46')
    expect(shiftWeek('2026-W01', -1)).toBe('2025-W52')
  })

  it('não sai dos anos de 2000 a 2100', () => {
    expect(shiftWeek('2000-W01', -1)).toBeNull()
    expect(shiftWeek('2100-W52', 5)).toBeNull()
    expect(shiftWeek('2000-W01', 1)).toBe('2000-W02')
  })

  it('o rótulo traz o número e os dias', () => {
    expect(weekLabel('2026-W46')).toBe('Semana 46 · 09/11 a 15/11/2026')
    expect(weekLabel('2025-W01')).toBe('Semana 1 · 30/12 a 05/01/2025')
  })
})
