import { useNavigate } from 'react-router'
import { createTransaction } from '../api/transactions'
import { Card } from '../design-system/src'
import { WriteGate } from '../permissions/WriteGate'
import { ErrorNotice } from '../ui/ErrorNotice'
import { LoadingNote } from '../ui/LoadingNote'
import { buildCreateTransaction, emptyTransactionForm } from '../validation/transactionForm'
import { useWorkspace } from '../workspace/useWorkspace'
import { useReferenceData } from './reference'
import { TransactionForm } from './TransactionForm'

const BACK = '/lancamentos'

/** `/lancamentos/novo`: registra uma receita, uma despesa ou uma transferência no workspace atual e volta à lista com o aviso. */
export function NewTransactionPage() {
  const { current } = useWorkspace()

  return (
    <WriteGate backTo={BACK} backLabel="Voltar aos lançamentos">
      <h1 className="heading-1">Novo lançamento</h1>
      <Card>{current ? <NewTransaction workspaceId={current.id} /> : null}</Card>
    </WriteGate>
  )
}

function NewTransaction({ workspaceId }: { workspaceId: string }) {
  const navigate = useNavigate()
  const reference = useReferenceData(workspaceId)

  if (reference.status === 'loading') return <LoadingNote>Carregando contas e categorias…</LoadingNote>
  if (reference.status === 'error') return <ErrorNotice onRetry={reference.reload}>Não foi possível carregar as contas e categorias. Tente de novo.</ErrorNotice>

  return (
    <TransactionForm
      mode="create"
      initial={emptyTransactionForm()}
      reference={reference}
      submitLabel="Registrar"
      cancelTo={BACK}
      onSubmit={async (values) => {
        await createTransaction(workspaceId, buildCreateTransaction(values))
        void navigate(BACK, { state: { notice: 'Lançamento registrado.' } })
      }}
    />
  )
}
