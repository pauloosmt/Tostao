import type { Recurrence } from '../api/types'

/** As ativas antes das encerradas; entre as ativas, a que ocorre antes vem primeiro (sem próxima, por último); depois, pela descrição. */
export function sortRecurrences(items: Recurrence[]): Recurrence[] {
  return [...items].sort((a, b) => {
    if (a.ended !== b.ended) return a.ended ? 1 : -1
    const next = (a.nextOccurrenceOn ?? '9999-12-31').localeCompare(b.nextOccurrenceOn ?? '9999-12-31')
    return next !== 0 ? next : a.description.localeCompare(b.description, 'pt-BR')
  })
}
