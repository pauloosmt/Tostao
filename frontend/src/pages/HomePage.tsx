import { useState } from 'react'
import { balanceReport, listAccounts } from '../api/accounts'
import { cardNote, toCardType } from '../accounts/accountTypes'
import { useAuth } from '../auth/useAuth'
import { useResource } from '../data/useResource'
import { AccountCard, Button, Card, PageHeader, WalletHero } from '../design-system/src'
import { useMediaQuery } from '../layout/useMediaQuery'
import { usePermissions } from '../permissions/usePermissions'
import { ButtonLink } from '../ui/ButtonLink'
import { ErrorNotice } from '../ui/ErrorNotice'
import { LoadingNote } from '../ui/LoadingNote'
import { roleLabel } from '../workspace/roles'
import { useWorkspace } from '../workspace/useWorkspace'

const TODAY = new Intl.DateTimeFormat('pt-BR', { weekday: 'long', day: 'numeric', month: 'long' })

export function HomePage() {
  const { state, logout } = useAuth()
  const { current } = useWorkspace()
  const [logoutFailed, setLogoutFailed] = useState(false)

  if (state.status !== 'authenticated') return null
  const firstName = state.me.name.trim().split(/\s+/)[0] || state.me.name
  const header = <PageHeader eyebrow={TODAY.format(new Date())} title={`Olá, ${firstName}`} />

  // Não deve ocorrer (o cadastro cria o workspace pessoal), mas nunca vira tela em branco.
  if (current === null) {
    return (
      <>
        {header}
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
      {header}
      <Wallet workspaceId={current.id} workspaceName={current.name} role={roleLabel(current.role)} />
    </>
  )
}

/** A Carteira (o saldo total das contas ativas) e, abaixo, as contas. O resumo do mês fica para a tela do dashboard. */
function Wallet({ workspaceId, workspaceName, role }: { workspaceId: string; workspaceName: string; role: string }) {
  const { canWrite } = usePermissions()
  const stacked = useMediaQuery('(max-width: 719px)')
  const resource = useResource(`home:${workspaceId}`, () =>
    Promise.all([listAccounts(workspaceId, false), balanceReport(workspaceId)]).then(([accounts, report]) => ({ accounts, total: report.totalBalanceCents })),
  )

  if (resource.status === 'loading') return <LoadingNote>Carregando suas contas…</LoadingNote>
  if (resource.status === 'error') return <ErrorNotice onRetry={resource.reload}>Não foi possível carregar suas contas. Tente de novo.</ErrorNotice>

  const { accounts, total } = resource.data
  const empty = accounts.length === 0

  return (
    <>
      <WalletHero
        label="Saldo total"
        cents={empty ? null : total}
        empty="Crie sua primeira conta"
        sub={`${workspaceName} · ${role}`}
        className={stacked ? 'tx-wallet--stack' : undefined}
      />

      {empty ? (
        <Card>
          <div className="stack stack--tight">
            <p className="text-body muted">
              {canWrite ? 'Com a primeira conta, os lançamentos e o saldo começam a aparecer aqui.' : 'Quem pode editar este workspace cria as contas.'}
            </p>
            {canWrite ? <ButtonLink variant="primary" to="/contas/nova">Criar conta</ButtonLink> : null}
          </div>
        </Card>
      ) : (
        <section className="stack" aria-labelledby="home-accounts">
          <h2 id="home-accounts" className="heading-2">Contas</h2>
          <ul className="account-list">
            {accounts.map((account) => (
              <li key={account.id}>
                <AccountCard name={account.name} type={toCardType(account.type)} balanceCents={account.balanceCents} note={cardNote(account)} />
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
  )
}
