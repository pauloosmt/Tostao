// O mês como texto `AAAA-MM`, em componentes locais (nunca `new Date('AAAA-MM')`, que cai no fuso). Funções puras.
import { MAX_YEAR, MIN_YEAR, todayISO } from './dates'

const NAMES = ['Janeiro', 'Fevereiro', 'Março', 'Abril', 'Maio', 'Junho', 'Julho', 'Agosto', 'Setembro', 'Outubro', 'Novembro', 'Dezembro']
const pad = (value: number, size = 2) => String(value).padStart(size, '0')

const parts = (month: string): { year: number; month: number } => ({ year: Number(month.slice(0, 4)), month: Number(month.slice(5, 7)) })

/** O mês de hoje, `AAAA-MM`. */
export const currentMonth = (today: string = todayISO()): string => today.slice(0, 7)

/** Anda `delta` meses (negativo volta). Fora de 2000-01 a 2100-12 devolve `null`. */
export function shiftMonth(month: string, delta: number): string | null {
  const { year, month: m } = parts(month)
  const index = year * 12 + (m - 1) + delta
  const nextYear = Math.floor(index / 12)
  if (nextYear < MIN_YEAR || nextYear > MAX_YEAR) return null
  return `${pad(nextYear, 4)}-${pad((index % 12) + 1)}`
}

/** "Novembro de 2026". */
export function monthLabel(month: string): string {
  const { year, month: m } = parts(month)
  return `${NAMES[m - 1] ?? ''} de ${year}`
}

/** Só o nome do mês, em minúsculas, para frases ("em novembro"). */
export function monthWord(month: string): string {
  return (NAMES[parts(month).month - 1] ?? '').toLowerCase()
}
