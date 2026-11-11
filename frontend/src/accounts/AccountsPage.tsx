import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { balanceReport, listAccounts } from '../api/accounts'
import { AccountCard, Alert, Card, StatTile } from '../design-system/src'
import { useResource } from '../data/useResource'
import { usePermissions } from '../permissions/usePermissions'
import { ButtonLink } from '../ui/ButtonLink'
import { CheckboxField } from '../ui/CheckboxField'
import { ErrorNotice } from '../ui/ErrorNotice'
import { LoadingNote } from '../ui/LoadingNote'
import { useWorkspace } from '../workspace/useWorkspace'
import { cardNote, toCardType } from './accountTypes'

export function AccountsPage() {
  const { current } = useWorkspace()

  return (
    <>
      <div className="page-header">
        <h1 className="heading-1">Contas</h1>
      </div>
      {current ? <AccountsOf workspaceId={current.id} /> : <Card><p className="text-body">Você não participa de nenhum workspace.</p></Card>}
    </>
  )
}

function AccountsOf({ workspaceId }: { workspaceId: string }) {
  const { canWrite } = usePermissions()
  const location = useLocation()
  const navigate = useNavigate()
  const [showArchived, setShowArchived] = useState(false)

  // O aviso do que acabou de acontecer (a conta foi criada) vem no estado do roteador, é lido uma vez e tirado do histórico.
  const [notice] = useState(() => (location.state as { notice?: string } | null)?.notice ?? null)
  useEffect(() => {
    if (notice !== null) void navigate(location.pathname, { replace: true, state: null })
  }, [notice, navigate, location.pathname])

  // A chave leva o workspace e a opção: trocar qualquer um volta a "carregando" e descarta a resposta que ficou para trás.
  const resource = useResource(`accounts:${workspaceId}:${showArchived}`, () =>
    Promise.all([listAccounts(workspaceId, showArchived), balanceReport(workspaceId)]).then(([accounts, report]) => ({ accounts, total: report.totalBalanceCents })),
  )

  return (
    <>
      {notice ? <Alert tone="info">{notice}</Alert> : null}

      <div className="list-toolbar">
        <CheckboxField label="Mostrar arquivadas" checked={showArchived} onChange={(event) => setShowArchived(event.target.checked)} />
        {canWrite ? <ButtonLink variant="primary" to="/contas/nova">Criar conta</ButtonLink> : null}
      </div>

      {resource.status === 'loading' ? <LoadingNote>Carregando contas…</LoadingNote> : null}

      {resource.status === 'error' ? <ErrorNotice onRetry={resource.reload}>Não foi possível carregar as contas. Tente de novo.</ErrorNotice> : null}

      {resource.status === 'ready' ? (
        <>
          <StatTile
            label="Saldo total"
            cents={resource.data.total}
            kind="balance"
            size="xl"
            hint={showArchived ? 'Considera só as contas ativas.' : undefined}
          />

          {resource.data.accounts.length === 0 ? (
            <Card>
              <div className="stack stack--tight">
                <p className="title">Nenhuma conta {showArchived ? '' : 'ainda'}</p>
                <p className="text-body muted">
                  {canWrite ? 'Crie a primeira conta para começar a acompanhar o seu saldo.' : 'Quem pode editar este workspace cria as contas.'}
                </p>
              </div>
            </Card>
          ) : (
            <ul className="account-list" aria-label="Contas">
              {resource.data.accounts.map((account) => (
                <li key={account.id} className="account-list__item">
                  <AccountCard
                    name={account.name}
                    type={toCardType(account.type)}
                    balanceCents={account.balanceCents}
                    note={cardNote(account)}
                    archived={account.archived}
                  />
                  {canWrite && !account.archived ? (
                    <div className="item-actions">
                      <ButtonLink size="sm" to={`/contas/${account.id}`} aria-label={`Editar ${account.name}`}>
                        Editar
                      </ButtonLink>
                    </div>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
        </>
      ) : null}
    </>
  )
}
