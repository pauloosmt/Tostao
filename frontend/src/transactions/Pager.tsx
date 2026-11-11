import { Button } from '../design-system/src'

export interface PagerProps {
  page: number
  totalPages: number
  onPage: (page: number) => void
  /** Desabilita os botões enquanto a página carrega. */
  busy?: boolean
}

/** "Página N de M" com "Anterior" e "Próxima", indisponíveis nos extremos. Com uma página só (ou nenhuma) não há o que mostrar. */
export function Pager({ page, totalPages, onPage, busy = false }: PagerProps) {
  if (totalPages <= 1) return null
  return (
    <nav className="pager" aria-label="Páginas de lançamentos">
      <Button variant="secondary" size="sm" disabled={busy || page <= 1} onClick={() => onPage(page - 1)}>
        Anterior
      </Button>
      <p className="text-body-sm pager__status" aria-live="polite">
        Página {page} de {totalPages}
      </p>
      <Button variant="secondary" size="sm" disabled={busy || page >= totalPages} onClick={() => onPage(page + 1)}>
        Próxima
      </Button>
    </nav>
  )
}
