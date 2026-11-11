// O resumo dos filtros ativos ("Conta: Carteira", "Período: 01/11/2026 a 30/11/2026") e a validação do painel de filtros. Puro.
import type { TransactionFilters } from '../api/transactions'
import { formatDate, parseISODate } from '../dates/dates'
import type { Names } from './rows'

const TYPE_LABEL = { INCOME: 'Receita', EXPENSE: 'Despesa', TRANSFER: 'Transferência' } as const
const STATUS_LABEL = { CLEARED: 'Efetivado', PENDING: 'Pendente' } as const

export interface FilterChip {
  key: string
  label: string
}

/** Uma etiqueta por filtro ativo, na ordem da tela. O período junta as duas datas numa só. */
export function summarize(filters: TransactionFilters, names: Names): FilterChip[] {
  const chips: FilterChip[] = []
  if (filters.from && filters.to) chips.push({ key: 'period', label: `Período: ${formatDate(filters.from)} a ${formatDate(filters.to)}` })
  else if (filters.from) chips.push({ key: 'period', label: `A partir de ${formatDate(filters.from)}` })
  else if (filters.to) chips.push({ key: 'period', label: `Até ${formatDate(filters.to)}` })
  if (filters.accountId) chips.push({ key: 'account', label: `Conta: ${names.accountName(filters.accountId)}` })
  if (filters.categoryId) chips.push({ key: 'category', label: `Categoria: ${names.categoryName(filters.categoryId) ?? 'removida'}` })
  if (filters.type) chips.push({ key: 'type', label: `Tipo: ${TYPE_LABEL[filters.type] ?? filters.type}` })
  if (filters.status) chips.push({ key: 'status', label: `Estado: ${STATUS_LABEL[filters.status] ?? filters.status}` })
  if (filters.q) chips.push({ key: 'q', label: `Busca: "${filters.q}"` })
  return chips
}

export type FilterField = 'from' | 'to' | 'q'
export const SEARCH_MAX = 100

export const FILTER_MESSAGES = {
  date: 'Informe uma data válida.',
  order: 'A data final não pode ser anterior à inicial.',
  search: `Use no máximo ${SEARCH_MAX} caracteres.`,
} as const

/** Os erros do painel: datas que existem, data final não anterior à inicial, busca de até 100 caracteres. Vazio = sem erro. */
export function validateFilters(draft: { from: string; to: string; q: string }): Partial<Record<FilterField, string>> {
  const errors: Partial<Record<FilterField, string>> = {}
  const from = draft.from.trim()
  const to = draft.to.trim()
  if (from !== '' && parseISODate(from) === null) errors.from = FILTER_MESSAGES.date
  if (to !== '' && parseISODate(to) === null) errors.to = FILTER_MESSAGES.date
  if (errors.from === undefined && errors.to === undefined && from !== '' && to !== '' && to < from) errors.to = FILTER_MESSAGES.order
  if (draft.q.trim().length > SEARCH_MAX) errors.q = FILTER_MESSAGES.search
  return errors
}
