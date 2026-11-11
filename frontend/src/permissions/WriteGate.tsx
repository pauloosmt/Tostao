import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { Card } from '../design-system/src'
import { roleLabel } from '../workspace/roles'
import { usePermissions } from './usePermissions'

export interface WriteGateProps {
  /** Para onde volta quem só pode consultar. */
  backTo: string
  backLabel: string
  children: ReactNode
}

/** Mostra o conteúdo (um formulário) a quem altera; ao leitor, o aviso de papel e o caminho de volta, nunca o formulário. */
export function WriteGate({ backTo, backLabel, children }: WriteGateProps) {
  const { role, canWrite } = usePermissions()
  if (canWrite) return <>{children}</>

  return (
    <>
      <h1 className="heading-1">Somente consulta</h1>
      <Card>
        <div className="stack">
          <p className="text-body">
            {role ? `Seu papel neste workspace é ${roleLabel(role).toLowerCase()}, que só permite consultar.` : 'Você não participa de nenhum workspace.'}
          </p>
          <Link className="link" to={backTo}>
            {backLabel}
          </Link>
        </div>
      </Card>
    </>
  )
}
