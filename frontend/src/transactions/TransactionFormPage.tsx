import { useNavigate, useParams } from 'react-router'
import { ApiError } from '../api/errors'
import { createTransaction, getTransaction, updateTransaction } from '../api/transactions'
import { Card } from '../design-system/src'
import { useResource } from '../data/useResource'
import { FAILURE_MESSAGES } from '../forms/apiErrors'
import { usePermissions } from '../permissions/usePermissions'
import { WriteGate } from '../permissions/WriteGate'
import { ErrorNotice } from '../ui/ErrorNotice'
import { LoadingNote } from '../ui/LoadingNote'
import { MissingItem } from '../ui/MissingItem'
import { buildCreateTransaction, buildUpdateTransaction, emptyTransactionForm, transactionToForm } from '../validation/transactionForm'
import { useWorkspace } from '../workspace/useWorkspace'
import { useReferenceData } from './reference'
import { TransactionDetails } from './TransactionDetails'
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

/** `/lancamentos/:id`: dono e editor editam; o leitor só consulta. Uma transferência abre com a origem e o destino reais, por qualquer ponta. */
export function TransactionPage() {
  const { current } = useWorkspace()
  const { id = '' } = useParams()

  return current ? <TransactionOf workspaceId={current.id} transactionId={id} /> : null
}

function TransactionOf({ workspaceId, transactionId }: { workspaceId: string; transactionId: string }) {
  const navigate = useNavigate()
  const { canWrite } = usePermissions()
  const reference = useReferenceData(workspaceId)
  const resource = useResource(`transaction:${workspaceId}:${transactionId}`, () => getTransaction(workspaceId, transactionId))

  if (resource.status === 'error' && resource.error instanceof ApiError && resource.error.status === 404) {
    return <MissingItem title="Lançamento não encontrado" backTo={BACK} backLabel="Voltar aos lançamentos" />
  }
  if (reference.status === 'error' || resource.status === 'error') {
    return (
      <ErrorNotice
        onRetry={() => {
          if (reference.status === 'error') reference.reload()
          if (resource.status === 'error') resource.reload()
        }}
      >
        Não foi possível carregar o lançamento. Tente de novo.
      </ErrorNotice>
    )
  }
  if (reference.status === 'loading' || resource.status === 'loading') return <LoadingNote>Carregando lançamento…</LoadingNote>

  const transaction = resource.data
  const title = transaction.type === 'TRANSFER' ? 'Transferência' : 'Lançamento'

  if (!canWrite) return <TransactionDetails transaction={transaction} reference={reference} title={title} />

  return (
    <>
      <h1 className="heading-1">{transaction.type === 'TRANSFER' ? 'Editar transferência' : 'Editar lançamento'}</h1>
      <Card>
        <TransactionForm
          key={transaction.id}
          mode="edit"
          initial={transactionToForm(transaction)}
          reference={reference}
          submitLabel="Salvar"
          cancelTo={BACK}
          onSubmit={async (values) => {
            try {
              await updateTransaction(workspaceId, transaction.id, buildUpdateTransaction(values))
            } catch (error) {
              // O lançamento deixou de existir (outro aparelho): volta à lista, que se atualiza, em vez de ficar num formulário sem destino.
              if (error instanceof ApiError && error.status === 404) {
                void navigate(BACK, { state: { notice: FAILURE_MESSAGES.notFound } })
                return
              }
              throw error
            }
            void navigate(BACK, { state: { notice: 'Lançamento salvo.' } })
          }}
        />
      </Card>
    </>
  )
}
