import { useOutletContext } from 'react-router'
import type { ReferenceData } from './reference'

/** O que a lista entrega às folhas de lançamento (criar e editar), que são suas rotas filhas. */
export interface SheetContext {
  workspaceId: string
  reference: ReferenceData
  /** Avisa que um lançamento mudou: a lista, que continua atrás da folha, se atualiza. */
  changed: () => void
}

export const useSheetContext = () => useOutletContext<SheetContext>()
