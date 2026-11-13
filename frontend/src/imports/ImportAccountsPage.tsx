import { Navigate } from 'react-router'
import { listAccounts } from '../api/accounts'
import { cardNote, toCardType } from '../accounts/accountTypes'
import { useResource } from '../data/useResource'
import { AccountCard, Card, PageHeader } from '../design-system/src'
import { WriteGate } from '../permissions/WriteGate'
import { ButtonLink } from '../ui/ButtonLink'
import { ErrorNotice } from '../ui/ErrorNotice'
import { LoadingNote } from '../ui/LoadingNote'
import { useWorkspace } from '../workspace/useWorkspace'

/** `/importar`: de qual conta é o extrato? Com uma única conta ativa, vai direto à importação dela. */
export function ImportAccountsPage() {
  const { current } = useWorkspace()
  return (
    <WriteGate backTo="/contas" backLabel="Voltar às contas">
      {current ? <Picker workspaceId={current.id} /> : null}
    </WriteGate>
  )
}

function Picker({ workspaceId }: { workspaceId: string }) {
  const resource = useResource(`import-accounts:${workspaceId}`, () => listAccounts(workspaceId, false))

  if (resource.status === 'loading') return <LoadingNote>Carregando contas…</LoadingNote>
  if (resource.status === 'error') return <ErrorNotice onRetry={resource.reload}>Não foi possível carregar as contas. Tente de novo.</ErrorNotice>

  const accounts = resource.data
  if (accounts.length === 1) return <Navigate to={`/contas/${accounts[0].id}/importar`} replace />

  return (
    <>
      <PageHeader title="Importar extrato" context="De qual conta é o extrato?" />
      {accounts.length === 0 ? (
        <Card>
          <div className="stack stack--tight">
            <p className="title">Nenhuma conta ainda</p>
            <p className="text-body muted">Crie a conta do extrato antes de importar.</p>
            <div><ButtonLink variant="primary" to="/contas/nova">Criar conta</ButtonLink></div>
          </div>
        </Card>
      ) : (
        <ul className="account-list" aria-label="Contas">
          {accounts.map((account) => (
            <li key={account.id} className="account-list__item">
              <AccountCard name={account.name} type={toCardType(account.type)} balanceCents={account.balanceCents} note={cardNote(account)} />
              <div className="item-actions">
                <ButtonLink variant="primary" size="sm" to={`/contas/${account.id}/importar`} aria-label={`Importar o extrato de ${account.name}`}>
                  Importar extrato
                </ButtonLink>
              </div>
            </li>
          ))}
        </ul>
      )}
    </>
  )
}
