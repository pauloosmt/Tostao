import { useCallback, useMemo, useState, type ReactNode } from 'react'
import type { TransactionFilters } from '../api/transactions'
import { useWorkspace } from '../workspace/useWorkspace'
import { FiltersContext, countActive, normalizeFilters, type FiltersValue } from './useFilters'

interface Stored {
  workspaceId: string
  filters: TransactionFilters
  page: number
}

/**
 * Guarda os filtros e a página da lista, por workspace, em memória. Ao trocar de workspace o estado volta ao padrão na hora (os filtros
 * apontam para contas e categorias do outro workspace e não valem mais); sair da conta desmonta o provider (o `RequireAuth` o remonta por pessoa).
 */
export function TransactionFiltersProvider({ children }: { children: ReactNode }) {
  const { current } = useWorkspace()
  const workspaceId = current?.id ?? ''
  const [stored, setStored] = useState<Stored>({ workspaceId, filters: {}, page: 1 })

  // Ajuste durante a renderização (o padrão que o React recomenda para "zerar o estado quando uma entrada muda"): sem efeito, sem piscar.
  if (stored.workspaceId !== workspaceId) setStored({ workspaceId, filters: {}, page: 1 })

  const apply = useCallback((filters: TransactionFilters) => setStored({ workspaceId, filters: normalizeFilters(filters), page: 1 }), [workspaceId])
  const clear = useCallback(() => setStored({ workspaceId, filters: {}, page: 1 }), [workspaceId])
  const setPage = useCallback((page: number) => setStored((now) => ({ ...now, workspaceId, page: Math.max(1, Math.trunc(page)) })), [workspaceId])

  const value = useMemo<FiltersValue>(
    () => ({ filters: stored.filters, page: stored.page, activeCount: countActive(stored.filters), apply, clear, setPage }),
    [stored.filters, stored.page, apply, clear, setPage],
  )

  return <FiltersContext.Provider value={value}>{children}</FiltersContext.Provider>
}
