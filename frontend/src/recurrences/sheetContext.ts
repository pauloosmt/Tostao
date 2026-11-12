import { useOutletContext } from 'react-router'
import type { ReferenceData } from '../transactions/reference'

/** O que a lista entrega às folhas de criar e de detalhes, que são suas rotas filhas. */
export interface RecurrenceSheetContext {
  workspaceId: string
  reference: ReferenceData
  /** Avisa que uma recorrência mudou: a lista, que continua atrás da folha, se atualiza. */
  changed: () => void
}

export const useSheetContext = () => useOutletContext<RecurrenceSheetContext>()
