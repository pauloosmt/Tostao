import type { ReactNode } from 'react'

/** O estado de espera de uma parte da tela (a lista que carrega). Anunciado a leitores de tela como um estado, não como conteúdo. */
export function LoadingNote({ children }: { children: ReactNode }) {
  return (
    <p className="text-body muted" role="status">
      {children}
    </p>
  )
}
