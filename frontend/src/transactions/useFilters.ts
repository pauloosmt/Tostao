// Os filtros e a página da lista de lançamentos. Vivem só na memória da sessão (nada no endereço, nada no armazenamento do navegador) e por
// workspace. O `TransactionFiltersProvider` (filters.tsx) os mantém; as telas só leem por aqui.
import { createContext, useContext } from 'react'
import type { TransactionFilters } from '../api/transactions'

export interface FiltersValue {
  /** Os filtros aplicados (só os preenchidos). */
  filters: TransactionFilters
  /** A página atual (a partir de 1). */
  page: number
  /** Quantos filtros estão ativos (para o "Filtros (N)"). */
  activeCount: number
  /** Aplica novos filtros e volta à primeira página. */
  apply: (filters: TransactionFilters) => void
  /** Tira todos os filtros e volta à primeira página. */
  clear: () => void
  setPage: (page: number) => void
}

export const FiltersContext = createContext<FiltersValue | null>(null)

export function useTransactionFilters(): FiltersValue {
  const value = useContext(FiltersContext)
  if (value === null) throw new Error('useTransactionFilters precisa estar dentro de um TransactionFiltersProvider')
  return value
}

const KEYS = ['from', 'to', 'accountId', 'categoryId', 'type', 'status', 'q'] as const

/** Só os filtros preenchidos: texto vazio (ou só espaços) vale como "sem filtro", e a busca é guardada sem os espaços das pontas. */
export function normalizeFilters(filters: TransactionFilters): TransactionFilters {
  const clean: Record<string, string> = {}
  for (const key of KEYS) {
    const value = filters[key]
    const text = value === undefined ? '' : key === 'q' ? value.trim() : value
    if (text !== '') clean[key] = text
  }
  return clean as TransactionFilters
}

export const countActive = (filters: TransactionFilters): number => Object.keys(normalizeFilters(filters)).length
