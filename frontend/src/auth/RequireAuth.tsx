import { Navigate, useLocation } from 'react-router'
import { AppShell } from '../layout/AppShell'
import { WorkspaceProvider } from '../workspace/WorkspaceContext'
import { useAuth } from './useAuth'

/**
 * A porta das telas de dentro. Sem sessão, vai para a entrada guardando o lugar pedido no ESTADO do roteador (nunca na consulta do
 * endereço). Com sessão, monta a estrutura do aplicativo; o `key` faz tudo reiniciar quando a pessoa muda, para a conta anterior
 * não deixar nada (estado de tela, escolha de workspace) na seguinte.
 */
export function RequireAuth() {
  const { state } = useAuth()
  const location = useLocation()

  if (state.status !== 'authenticated') return <Navigate to="/entrar" replace state={{ from: location }} />

  return (
    <WorkspaceProvider key={state.me.id} me={state.me}>
      <AppShell />
    </WorkspaceProvider>
  )
}
