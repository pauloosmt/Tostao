// Datas de calendário (`AAAA-MM-DD`), sempre como componentes LOCAIS. Nunca `toISOString()` (usa UTC: à noite no Brasil daria o dia seguinte)
// nem `new Date('AAAA-MM-DD')` (também lê como UTC: daria o dia anterior). Uma data de lançamento não tem fuso nem hora.

export const MIN_YEAR = 2000
export const MAX_YEAR = 2100

export const DATE_MESSAGES = {
  empty: 'Informe a data.',
  invalid: 'Informe uma data válida.',
  year: `Use um ano de ${MIN_YEAR} a ${MAX_YEAR}.`,
} as const

const pad = (value: number, width = 2) => String(value).padStart(width, '0')

export const toISO = (year: number, month: number, day: number): string => `${pad(year, 4)}-${pad(month)}-${pad(day)}`

/** Hoje, no calendário do aparelho (por padrão do relógio dele). */
export function todayISO(now: Date = new Date()): string {
  return toISO(now.getFullYear(), now.getMonth() + 1, now.getDate())
}

export interface CalendarDate {
  year: number
  month: number
  day: number
}

/** Lê `AAAA-MM-DD` e confere que o dia existe no calendário (31 de abril e 29 de fevereiro de ano comum não existem); senão, `null`. */
export function parseISODate(text: string): CalendarDate | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text)
  if (!match) return null
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  // `new Date(ano, ...)` trata anos de 0 a 99 como 19xx; `setFullYear` usa o ano como veio. A conferência de ida e volta pega o dia que não existe.
  const check = new Date(2000, 0, 1)
  check.setFullYear(year, month - 1, day)
  const same = check.getFullYear() === year && check.getMonth() === month - 1 && check.getDate() === day
  return same && month >= 1 && month <= 12 ? { year, month, day } : null
}

/** A mensagem do campo de data de um lançamento (ou `undefined` se vale): vazio, inexistente ou fora de 2000 a 2100. */
export function validateDateText(text: string): string | undefined {
  const value = text.trim()
  if (value === '') return DATE_MESSAGES.empty
  const date = parseISODate(value)
  if (date === null) return DATE_MESSAGES.invalid
  if (date.year < MIN_YEAR || date.year > MAX_YEAR) return DATE_MESSAGES.year
  return undefined
}

/** O dia anterior, no calendário. */
export function previousDay(iso: string): string {
  const date = parseISODate(iso)
  if (date === null) return iso
  const before = new Date(date.year, date.month - 1, date.day - 1)
  return toISO(before.getFullYear(), before.getMonth() + 1, before.getDate())
}

/** `2026-11-03` -> `03/11/2026` (para resumos e filtros). Um texto que não é data volta como veio. */
export function formatDate(iso: string): string {
  const date = parseISODate(iso)
  return date === null ? iso : `${pad(date.day)}/${pad(date.month)}/${pad(date.year, 4)}`
}

const MONTHS = ['jan.', 'fev.', 'mar.', 'abr.', 'mai.', 'jun.', 'jul.', 'ago.', 'set.', 'out.', 'nov.', 'dez.']

/** O título do grupo de um dia: "Hoje", "Ontem" ou "3 de nov. de 2026". Um texto que não é data volta como veio. */
export function formatDay(iso: string, today: string = todayISO()): string {
  if (iso === today) return 'Hoje'
  if (iso === previousDay(today)) return 'Ontem'
  const date = parseISODate(iso)
  return date === null ? iso : `${date.day} de ${MONTHS[date.month - 1]} de ${date.year}`
}
