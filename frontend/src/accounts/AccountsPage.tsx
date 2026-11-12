import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { archiveAccount, balanceReport, listAccounts, restoreAccount } from '../api/accounts'
import type { Account } from '../api/types'
import { AccountCard, Alert, Button, Card, PageHeader, StatTile } from '../design-system/src'
import { useResource } from '../data/useResource'
import { describeFailure } from '../forms/apiErrors'
import { useSubmit } from '../forms/useSubmit'
import { useMediaQuery } from '../layout/useMediaQuery'
import { usePermissions } from '../permissions/usePermissions'
import { ButtonLink } from '../ui/ButtonLink'
import { CheckboxField } from '../ui/CheckboxField'
import { ConfirmDialog } from '../ui/ConfirmDialog'
import { ErrorNotice } from '../ui/ErrorNotice'
import { LoadingNote } from '../ui/LoadingNote'
import { useWorkspace } from '../workspace/useWorkspace'
import { cardNote, toCardType } from './accountTypes'

const RESTORE_CONFLICT = 'Já existe uma conta ativa com este nome. Renomeie a ativa antes de restaurar esta.'

export function AccountsPage() {
  const { current } = useWorkspace()
  const { canWrite } = usePermissions()
  // Na barra de baixo (celular) não cabem os itens Orçamentos e Categorias: os atalhos ficam aqui. No menu lateral ele já existe.
  const wide = useMediaQuery('(min-width: 960px)')

  return (
    <>
      <PageHeader
        title="Contas"
        action={
          <>
            {wide ? null : <ButtonLink variant="secondary" to="/orcamentos">Orçamentos</ButtonLink>}
            {wide ? null : <ButtonLink variant="secondary" to="/categorias">Categorias</ButtonLink>}
            {canWrite ? <ButtonLink variant="primary" to="/contas/nova">Criar conta</ButtonLink> : null}
          </>
        }
      />
      {current ? <AccountsOf workspaceId={current.id} /> : <Card><p className="text-body">Você não participa de nenhum workspace.</p></Card>}
    </>
  )
}

function AccountsOf({ workspaceId }: { workspaceId: string }) {
  const { canWrite } = usePermissions()
  const location = useLocation()
  const navigate = useNavigate()
  const { submitting, run } = useSubmit()
  const [showArchived, setShowArchived] = useState(false)
  const [archiving, setArchiving] = useState<Account | null>(null)
  const [problem, setProblem] = useState<string | null>(null)

  // O aviso do que acabou de acontecer vem no estado do roteador (a conta foi criada ou salva), é lido uma vez e tirado do histórico;
  // os avisos das ações desta tela (arquivada, restaurada) entram no mesmo lugar.
  const [initialNotice] = useState(() => (location.state as { notice?: string } | null)?.notice ?? null)
  const [notice, setNotice] = useState<string | null>(initialNotice)
  useEffect(() => {
    if (initialNotice !== null) void navigate(location.pathname, { replace: true, state: null })
  }, [initialNotice, navigate, location.pathname])

  // A chave leva o workspace e a opção: trocar qualquer um volta a "carregando" e descarta a resposta que ficou para trás.
  const resource = useResource(`accounts:${workspaceId}:${showArchived}`, () =>
    Promise.all([listAccounts(workspaceId, showArchived), balanceReport(workspaceId)]).then(([accounts, report]) => ({ accounts, total: report.totalBalanceCents })),
  )
  const { reload } = resource

  /** Uma ação sobre uma conta (arquivar, restaurar): o sucesso recarrega a lista e o total; a falha deixa tudo como estava e explica. */
  async function act(action: () => Promise<unknown>, done: string, conflict?: string) {
    await run(async () => {
      setNotice(null)
      setProblem(null)
      try {
        await action()
        setNotice(done)
        reload()
      } catch (error) {
        const failure = describeFailure(error, { conflictMessage: conflict })
        setProblem(failure.general ?? null)
        // A conta deixou de existir em outro aparelho: a lista se atualiza para refletir isso.
        if (failure.kind === 'notFound') reload()
      } finally {
        setArchiving(null)
      }
    })
  }

  return (
    <>
      {notice ? <Alert tone="info">{notice}</Alert> : null}
      {problem ? <Alert tone="danger">{problem}</Alert> : null}

      <div className="list-toolbar">
        <CheckboxField label="Mostrar arquivadas" checked={showArchived} onChange={(event) => setShowArchived(event.target.checked)} />
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
                  {canWrite ? (
                    <div className="item-actions">
                      {account.archived ? (
                        <Button
                          size="sm"
                          variant="secondary"
                          disabled={submitting}
                          aria-label={`Restaurar ${account.name}`}
                          onClick={() => void act(() => restoreAccount(workspaceId, account.id), 'Conta restaurada.', RESTORE_CONFLICT)}
                        >
                          Restaurar
                        </Button>
                      ) : (
                        <>
                          <ButtonLink size="sm" to={`/contas/${account.id}`} aria-label={`Editar ${account.name}`}>
                            Editar
                          </ButtonLink>
                          <Button size="sm" variant="secondary" disabled={submitting} aria-label={`Arquivar ${account.name}`} onClick={() => setArchiving(account)}>
                            Arquivar
                          </Button>
                        </>
                      )}
                    </div>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
        </>
      ) : null}

      <ConfirmDialog
        open={archiving !== null}
        tone="primary"
        title={`Arquivar "${archiving?.name ?? ''}"?`}
        confirmLabel="Arquivar"
        busy={submitting}
        onConfirm={() => {
          if (archiving) void act(() => archiveAccount(workspaceId, archiving.id), 'Conta arquivada.')
        }}
        onCancel={() => setArchiving(null)}
      >
        A conta sai da lista e do saldo total. Os lançamentos dela continuam existindo, e você pode restaurá-la quando quiser.
      </ConfirmDialog>
    </>
  )
}
