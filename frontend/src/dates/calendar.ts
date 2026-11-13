// Datas do calendário do sistema: tudo em texto `AAAA-MM-DD` (como a API) e em dia local, sem fuso e sem `Date.parse`.

export const MONTH_NAMES = ['janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho', 'julho', 'agosto', 'setembro', 'outubro', 'novembro', 'dezembro'] as const
/** Domingo primeiro, como o calendário que o app mostrava antes. */
export const WEEKDAY_INITIALS = ['D', 'S', 'T', 'Q', 'Q', 'S', 'S'] as const
export const WEEKDAY_NAMES = ['domingo', 'segunda-feira', 'terça-feira', 'quarta-feira', 'quinta-feira', 'sexta-feira', 'sábado'] as const

const ISO = /^(\d{4})-(\d{2})-(\d{2})$/
const BR = /^(\d{2})\/(\d{2})\/(\d{4})$/

export interface Ymd {
  year: number
  /** 0 a 11. */
  month: number
  day: number
}

const pad = (n: number, size = 2) => String(n).padStart(size, '0')

function valid(year: number, month: number, day: number): boolean {
  if (year < 1900 || year > 2100 || month < 0 || month > 11 || day < 1) return false
  return day <= new Date(year, month + 1, 0).getDate()
}

export function toIso({ year, month, day }: Ymd): string {
  return `${pad(year, 4)}-${pad(month + 1)}-${pad(day)}`
}

/** `"2026-11-13"` → partes; `null` se não for uma data de verdade (31/02, mês 13, formato errado). */
export function fromIso(text: string): Ymd | null {
  const match = ISO.exec(text)
  if (!match) return null
  const parts = { year: Number(match[1]), month: Number(match[2]) - 1, day: Number(match[3]) }
  return valid(parts.year, parts.month, parts.day) ? parts : null
}

/** `"13/11/2026"` → `"2026-11-13"`; `null` se incompleta ou inexistente. */
export function brToIso(text: string): string | null {
  const match = BR.exec(text)
  if (!match) return null
  const parts = { year: Number(match[3]), month: Number(match[2]) - 1, day: Number(match[1]) }
  return valid(parts.year, parts.month, parts.day) ? toIso(parts) : null
}

/** `"2026-11-13"` → `"13/11/2026"`; vazio ou inválido → `""`. */
export function isoToBr(iso: string): string {
  const parts = fromIso(iso)
  return parts ? `${pad(parts.day)}/${pad(parts.month + 1)}/${pad(parts.year, 4)}` : ''
}

/** Só os dígitos (até 8) viram `dd/mm/aaaa`: as barras entram sozinhas enquanto se digita. */
export function maskBr(typed: string): string {
  const digits = typed.replace(/\D/g, '').slice(0, 8)
  if (digits.length <= 2) return digits
  if (digits.length <= 4) return `${digits.slice(0, 2)}/${digits.slice(2)}`
  return `${digits.slice(0, 2)}/${digits.slice(2, 4)}/${digits.slice(4)}`
}

export function todayIso(now = new Date()): string {
  return toIso({ year: now.getFullYear(), month: now.getMonth(), day: now.getDate() })
}

/** Soma dias (pode ser negativo) a uma data. */
export function addDays(iso: string, days: number): string {
  const p = fromIso(iso)
  if (!p) return iso
  const d = new Date(p.year, p.month, p.day + days)
  return toIso({ year: d.getFullYear(), month: d.getMonth(), day: d.getDate() })
}

/** Soma meses, mantendo o dia quando existe e usando o último dia do mês quando não (31/01 + 1 mês = 28/02). */
export function addMonths(iso: string, months: number): string {
  const p = fromIso(iso)
  if (!p) return iso
  const target = new Date(p.year, p.month + months, 1)
  const last = new Date(target.getFullYear(), target.getMonth() + 1, 0).getDate()
  return toIso({ year: target.getFullYear(), month: target.getMonth(), day: Math.min(p.day, last) })
}

export interface CalendarDay {
  iso: string
  day: number
  inMonth: boolean
}

/** Seis semanas (42 dias), domingo a sábado, com os dias dos meses vizinhos que completam as pontas. */
export function monthGrid(year: number, month: number): CalendarDay[][] {
  const first = new Date(year, month, 1)
  const start = new Date(year, month, 1 - first.getDay())
  const weeks: CalendarDay[][] = []
  for (let w = 0; w < 6; w++) {
    const week: CalendarDay[] = []
    for (let d = 0; d < 7; d++) {
      const date = new Date(start.getFullYear(), start.getMonth(), start.getDate() + w * 7 + d)
      week.push({ iso: toIso({ year: date.getFullYear(), month: date.getMonth(), day: date.getDate() }), day: date.getDate(), inMonth: date.getMonth() === month })
    }
    weeks.push(week)
  }
  return weeks
}

export function monthLabel(year: number, month: number): string {
  return `${MONTH_NAMES[month]} de ${year}`
}

/** `"2026-11-13"` → `"13 de novembro de 2026"` (o nome do dia para leitores de tela). */
export function longLabel(iso: string): string {
  const p = fromIso(iso)
  return p ? `${p.day} de ${MONTH_NAMES[p.month]} de ${p.year}` : ''
}
