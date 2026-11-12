// A semana ISO 8601 (de segunda a domingo) como texto `AAAA-Www`, em componentes locais (nunca pelo fuso). Funções puras.
import { MAX_YEAR, MIN_YEAR, formatDate, todayISO } from './dates'

const DAY = 86_400_000
const pad = (value: number, size = 2) => String(value).padStart(size, '0')
const isoOf = (date: Date) => `${pad(date.getFullYear(), 4)}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
const utcDay = (date: Date) => Date.UTC(date.getFullYear(), date.getMonth(), date.getDate())

/** A semana ISO de uma data `AAAA-MM-DD`: o ano dela é o da quinta-feira daquela semana. */
export function weekOf(iso: string): string {
  const [year, month, day] = iso.split('-').map(Number)
  const date = new Date(year, month - 1, day)
  const monday = (date.getDay() + 6) % 7 // segunda = 0
  const thursday = new Date(year, month - 1, day - monday + 3)
  const isoYear = thursday.getFullYear()
  const week = Math.floor((utcDay(thursday) - Date.UTC(isoYear, 0, 1)) / DAY / 7) + 1
  return `${pad(isoYear, 4)}-W${pad(week)}`
}

/** A semana de hoje. */
export const currentWeek = (today: string = todayISO()): string => weekOf(today)

/** A segunda-feira da semana `AAAA-Www`. */
function mondayOf(week: string): Date {
  const year = Number(week.slice(0, 4))
  const number = Number(week.slice(6, 8))
  const jan4 = new Date(year, 0, 4) // o dia 4 de janeiro está sempre na semana 1
  const offset = (jan4.getDay() + 6) % 7
  return new Date(year, 0, 4 - offset + (number - 1) * 7)
}

/** De segunda a domingo da semana, `AAAA-MM-DD`. */
export function weekRange(week: string): { start: string; end: string } {
  const monday = mondayOf(week)
  return { start: isoOf(monday), end: isoOf(new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + 6)) }
}

/** Anda `delta` semanas (negativo volta). Fora dos anos de 2000 a 2100 devolve `null`. */
export function shiftWeek(week: string, delta: number): string | null {
  const monday = mondayOf(week)
  const next = weekOf(isoOf(new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + delta * 7)))
  const year = Number(next.slice(0, 4))
  return year < MIN_YEAR || year > MAX_YEAR ? null : next
}

/** "Semana 46 · 09/11 a 15/11/2026". */
export function weekLabel(week: string): string {
  const { start, end } = weekRange(week)
  return `Semana ${Number(week.slice(6, 8))} · ${formatDate(start).slice(0, 5)} a ${formatDate(end)}`
}
