import { useState } from 'react'
import { getDashboard } from '../api/dashboard'
import { useAuth } from '../auth/useAuth'
import { useResource } from '../data/useResource'
import { currentMonth, monthLabel, shiftMonth } from '../dates/months'
import { Button, Card, MonthSwitcher, PageHeader } from '../design-system/src'
import { Dashboard } from '../home/Dashboard'
import { usePermissions } from '../permissions/usePermissions'
import { ErrorNotice } from '../ui/ErrorNotice'
import { LoadingNote } from '../ui/LoadingNote'
import { roleLabel } from '../workspace/roles'
import { useWorkspace } from '../workspace/useWorkspace'

const TODAY = new Intl.DateTimeFormat('pt-BR', { weekday: 'long', day: 'numeric', month: 'long' })

export function HomePage() {
  const { state, logout } = useAuth()
  const { current } = useWorkspace()
  const [logoutFailed, setLogoutFailed] = useState(false)
  const [today] = useState(() => TODAY.format(new Date()))

  if (state.status !== 'authenticated') return null
  const firstName = state.me.name.trim().split(/\s+/)[0] || state.me.name

  // Não deve ocorrer (o cadastro cria o workspace pessoal), mas nunca vira tela em branco.
  if (current === null) {
    return (
      <>
        <PageHeader eyebrow={today} title={`Olá, ${firstName}`} />
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

  return <HomeOf workspaceId={current.id} workspaceLine={`${current.name} · ${roleLabel(current.role)}`} eyebrow={today} firstName={firstName} />
}

interface HomeOfProps {
  workspaceId: string
  workspaceLine: string
  eyebrow: string
  firstName: string
}

/** O Início de um workspace: o mês escolhido (o de hoje ao abrir) e o painel dele. Trocar o mês volta a "carregando". */
function HomeOf({ workspaceId, workspaceLine, eyebrow, firstName }: HomeOfProps) {
  const { canWrite } = usePermissions()
  const [month, setMonth] = useState(() => currentMonth())
  const resource = useResource(`dashboard:${workspaceId}:${month}`, () => getDashboard(workspaceId, month))
  const go = (delta: number) => setMonth((current) => shiftMonth(current, delta) ?? current)

  return (
    <>
      <PageHeader
        eyebrow={eyebrow}
        title={`Olá, ${firstName}`}
        action={<MonthSwitcher label={monthLabel(month)} onPrev={() => go(-1)} onNext={() => go(1)} />}
      />
      {resource.status === 'loading' ? <LoadingNote>Carregando o resumo do mês…</LoadingNote> : null}
      {resource.status === 'error' ? <ErrorNotice onRetry={resource.reload}>Não foi possível carregar o resumo do mês. Tente de novo.</ErrorNotice> : null}
      {resource.status === 'ready' ? <Dashboard data={resource.data} month={month} workspaceLine={workspaceLine} canWrite={canWrite} /> : null}
    </>
  )
}
