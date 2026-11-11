import { useState } from 'react'
import { useAuth } from '../auth/useAuth'
import { Badge, Button, Card } from '../design-system/src'
import { ErrorNotice } from '../ui/ErrorNotice'
import { roleLabel } from '../workspace/roles'
import { useWorkspace } from '../workspace/useWorkspace'

export function HomePage() {
  const { state, logout } = useAuth()
  const { current } = useWorkspace()
  const [logoutFailed, setLogoutFailed] = useState(false)

  if (state.status !== 'authenticated') return null
  const firstName = state.me.name.trim().split(/\s+/)[0] || state.me.name

  // Não deve ocorrer (o cadastro cria o workspace pessoal), mas nunca vira tela em branco.
  if (current === null) {
    return (
      <>
        <h1 className="heading-1">Olá, {firstName}</h1>
        <Card title="Nenhum workspace">
          <div className="stack">
            <p className="text-body">Sua conta não participa de nenhum workspace, então ainda não há o que mostrar.</p>
            {logoutFailed ? <ErrorNotice>Não foi possível sair. Tente de novo.</ErrorNotice> : null}
            <Button
              variant="secondary"
              onClick={() => {
                setLogoutFailed(false)
                logout().catch(() => setLogoutFailed(true))
              }}
            >
              Sair
            </Button>
          </div>
        </Card>
      </>
    )
  }

  return (
    <>
      <h1 className="heading-1">Olá, {firstName}</h1>
      <Card title="Workspace atual">
        <div className="stack stack--tight">
          <p className="title">{current.name}</p>
          <div>
            <Badge tone="brand">{roleLabel(current.role)}</Badge>
          </div>
          <p className="text-body-sm muted">Moeda base: {current.baseCurrency}</p>
        </div>
      </Card>
      <Card>
        <div className="stack stack--tight">
          <p className="title">Ainda não há nada por aqui</p>
          <p className="text-body muted">Contas, lançamentos e o resumo do mês vão aparecer aqui nas próximas versões.</p>
        </div>
      </Card>
    </>
  )
}
