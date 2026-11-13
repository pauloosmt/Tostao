import { useParams } from 'react-router'
import { ApiError } from '../api/errors'
import { getAccount } from '../api/accounts'
import { useResource } from '../data/useResource'
import { Alert, Card, PageHeader } from '../design-system/src'
import { WriteGate } from '../permissions/WriteGate'
import { ButtonLink } from '../ui/ButtonLink'
import { ErrorNotice } from '../ui/ErrorNotice'
import { LoadingNote } from '../ui/LoadingNote'
import { MissingItem } from '../ui/MissingItem'
import { useReferenceData } from '../transactions/reference'
import { useWorkspace } from '../workspace/useWorkspace'
import { ImportFlow } from './ImportFlow'

const BACK = '/contas'

/** `/contas/:id/importar`: envia o extrato da conta, mostra a prévia e confirma a importação. Só quem altera chega aqui. */
export function ImportPage() {
  const { current } = useWorkspace()
  const { id = '' } = useParams()

  return (
    <WriteGate backTo={BACK} backLabel="Voltar às contas">
      {current ? <ImportOf workspaceId={current.id} accountId={id} /> : null}
    </WriteGate>
  )
}

function ImportOf({ workspaceId, accountId }: { workspaceId: string; accountId: string }) {
  const account = useResource(`account:${workspaceId}:${accountId}`, () => getAccount(workspaceId, accountId))
  const reference = useReferenceData(workspaceId)

  if (account.status === 'loading' || reference.status === 'loading') return <LoadingNote>Carregando a conta…</LoadingNote>
  if (account.status === 'error') {
    if (account.error instanceof ApiError && account.error.status === 404) return <MissingItem title="Conta não encontrada" backTo={BACK} backLabel="Voltar às contas" />
    return <ErrorNotice onRetry={account.reload}>Não foi possível carregar a conta. Tente de novo.</ErrorNotice>
  }
  if (reference.status === 'error') return <ErrorNotice onRetry={reference.reload}>Não foi possível carregar as categorias. Tente de novo.</ErrorNotice>

  if (account.data.archived) {
    return (
      <>
        <PageHeader title="Importar extrato" context={account.data.name} />
        <Card>
          <div className="stack">
            <Alert tone="warning">A conta está arquivada. Restaure a conta antes de importar um extrato nela.</Alert>
            <div><ButtonLink to={BACK}>Voltar às contas</ButtonLink></div>
          </div>
        </Card>
      </>
    )
  }

  return <ImportFlow workspaceId={workspaceId} account={account.data} reference={reference} />
}
