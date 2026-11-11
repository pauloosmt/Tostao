import { useCallback, useMemo, useState, type ReactNode } from 'react'
import type { Me } from '../api/types'
import { getItem, setItem, workspaceKey } from '../storage/local'
import { WorkspaceContext, type WorkspaceValue } from './useWorkspace'

/**
 * O workspace atual é o último escolhido neste aparelho, por pessoa; se não há escolha ou ela já não vale (a pessoa saiu daquele
 * workspace, ou o valor guardado é de outra conta), é o primeiro da lista. Só o identificador é guardado.
 */
export function WorkspaceProvider({ me, children }: { me: Me; children: ReactNode }) {
  // A escolha vem com o dono: ao trocar de pessoa, a escolha da anterior não vale e relê-se a guardada da nova.
  const [chosen, setChosen] = useState<{ userId: string; id: string | null }>(() => ({ userId: me.id, id: getItem(workspaceKey(me.id)) }))
  const chosenId = chosen.userId === me.id ? chosen.id : getItem(workspaceKey(me.id))

  const current = me.workspaces.find((workspace) => workspace.id === chosenId) ?? me.workspaces[0] ?? null

  const select = useCallback(
    (id: string) => {
      if (!me.workspaces.some((workspace) => workspace.id === id)) return
      setChosen({ userId: me.id, id })
      setItem(workspaceKey(me.id), id)
    },
    [me],
  )

  const value = useMemo<WorkspaceValue>(() => ({ current, workspaces: me.workspaces, select }), [current, me.workspaces, select])

  return <WorkspaceContext.Provider value={value}>{children}</WorkspaceContext.Provider>
}
