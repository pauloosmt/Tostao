// O workspace atual da pessoa. O `WorkspaceProvider` (WorkspaceContext.tsx) o mantém; as telas só leem por aqui.
import { createContext, useContext } from 'react'
import type { WorkspaceMembership } from '../api/types'

export interface WorkspaceValue {
  /** O workspace em uso; `null` só se a pessoa não participa de nenhum (a tela explica e oferece "Sair"). */
  current: WorkspaceMembership | null
  workspaces: readonly WorkspaceMembership[]
  /** Troca o workspace atual e guarda a escolha neste aparelho. Um identificador que não é da pessoa é ignorado. */
  select: (id: string) => void
}

export const WorkspaceContext = createContext<WorkspaceValue | null>(null)

export function useWorkspace(): WorkspaceValue {
  const value = useContext(WorkspaceContext)
  if (value === null) throw new Error('useWorkspace precisa estar dentro de um WorkspaceProvider')
  return value
}
