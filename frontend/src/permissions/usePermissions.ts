import type { Role } from '../api/types'
import { useWorkspace } from '../workspace/useWorkspace'

export interface Permissions {
  /** O papel no workspace atual; `null` se a pessoa não participa de nenhum. */
  role: Role | null
  /** Dono e editor alteram; leitor só consulta. */
  canWrite: boolean
}

/**
 * O que a pessoa pode fazer no workspace atual. Serve só para escolher o que mostrar (esconder o botão que a API recusaria):
 * quem decide é a API, que continua respondendo 403 ao que o papel não permite (o papel pode mudar em outro aparelho).
 */
export function usePermissions(): Permissions {
  const { current } = useWorkspace()
  const role = current?.role ?? null
  return { role, canWrite: role === 'OWNER' || role === 'EDITOR' }
}
