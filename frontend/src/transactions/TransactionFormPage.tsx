import { Navigate, useNavigate, useParams } from 'react-router'
import { ApiError } from '../api/errors'
import { createTransaction, getTransaction, updateTransaction } from '../api/transactions'
import { useResource } from '../data/useResource'
import { FAILURE_MESSAGES } from '../forms/apiErrors'
import { usePermissions } from '../permissions/usePermissions'
import { ErrorNotice } from '../ui/ErrorNotice'
import { LoadingNote } from '../ui/LoadingNote'
import { SheetDialog } from '../ui/SheetDialog'
import { buildCreateTransaction, buildUpdateTransaction, emptyTransactionForm, transactionToForm } from '../validation/transactionForm'
import { useSheetContext } from './sheetContext'
import { TransactionActions } from './TransactionActions'
import { TransactionDetails } from './TransactionDetails'
import { TransactionForm } from './TransactionForm'

const BACK = '/lancamentos'

/** `/lancamentos/novo`: a folha que registra uma receita, uma despesa ou uma transferência; fecha com o aviso e a lista atrás se atualiza. */
export function NewTransactionSheet() {
  const { workspaceId, reference, changed } = useSheetContext()
  const { canWrite } = usePermissions()
  const navigate = useNavigate()

  if (!canWrite) return <Navigate to={BACK} replace state={{ notice: 'Este workspace é só para consulta: você não pode registrar lançamentos.' }} />

  return (
    <TransactionForm
      mode="create"
      title="Novo lançamento"
      initial={emptyTransactionForm()}
      reference={reference}
      submitLabel="Registrar"
      onClose={() => void navigate(BACK, { replace: true })}
      onSubmit={async (values) => {
        await createTransaction(workspaceId, buildCreateTransaction(values))
        changed()
        void navigate(BACK, { replace: true, state: { notice: 'Lançamento registrado.' } })
      }}
    />
  )
}

/** `/lancamentos/:id`: dono e editor editam na folha; o leitor só consulta. Uma transferência abre com a origem e o destino reais, por qualquer ponta. */
export function TransactionSheet() {
  const { workspaceId, reference, changed } = useSheetContext()
  const { id = '' } = useParams()
  const { canWrite } = usePermissions()
  const navigate = useNavigate()
  const resource = useResource(`transaction:${workspaceId}:${id}`, () => getTransaction(workspaceId, id))
  const close = () => void navigate(BACK, { replace: true })

  if (resource.status === 'error' && resource.error instanceof ApiError && resource.error.status === 404) {
    return <Navigate to={BACK} replace state={{ notice: FAILURE_MESSAGES.notFound }} />
  }
  if (resource.status === 'error') {
    return (
      <SheetDialog title="Lançamento" onClose={close}>
        <ErrorNotice onRetry={resource.reload}>Não foi possível carregar o lançamento. Tente de novo.</ErrorNotice>
      </SheetDialog>
    )
  }
  if (resource.status === 'loading') {
    return (
      <SheetDialog title="Lançamento" onClose={close}>
        <LoadingNote>Carregando lançamento…</LoadingNote>
      </SheetDialog>
    )
  }

  const transaction = resource.data
  const transfer = transaction.type === 'TRANSFER'

  if (!canWrite) {
    return (
      <SheetDialog title={transfer ? 'Transferência' : 'Lançamento'} onClose={close}>
        <TransactionDetails transaction={transaction} reference={reference} />
      </SheetDialog>
    )
  }

  return (
    <TransactionForm
      key={transaction.id}
      mode="edit"
      title={transfer ? 'Editar transferência' : 'Editar lançamento'}
      initial={transactionToForm(transaction)}
      reference={reference}
      submitLabel="Salvar"
      onClose={close}
      after={<TransactionActions workspaceId={workspaceId} transaction={transaction} onChanged={changed} />}
      onSubmit={async (values) => {
        try {
          await updateTransaction(workspaceId, transaction.id, buildUpdateTransaction(values))
        } catch (error) {
          // O lançamento deixou de existir (outro aparelho): volta à lista, que se atualiza, em vez de ficar num formulário sem destino.
          if (error instanceof ApiError && error.status === 404) {
            changed()
            void navigate(BACK, { replace: true, state: { notice: FAILURE_MESSAGES.notFound } })
            return
          }
          throw error
        }
        changed()
        void navigate(BACK, { replace: true, state: { notice: 'Lançamento salvo.' } })
      }}
    />
  )
}
