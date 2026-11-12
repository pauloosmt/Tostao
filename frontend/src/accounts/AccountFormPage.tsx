import { useNavigate, useParams } from 'react-router'
import { createAccount, getAccount, updateAccount } from '../api/accounts'
import { ApiError } from '../api/errors'
import { Card, PageHeader } from '../design-system/src'
import { useResource } from '../data/useResource'
import { FAILURE_MESSAGES } from '../forms/apiErrors'
import { WriteGate } from '../permissions/WriteGate'
import { ButtonLink } from '../ui/ButtonLink'
import { ErrorNotice } from '../ui/ErrorNotice'
import { LoadingNote } from '../ui/LoadingNote'
import { MissingItem } from '../ui/MissingItem'
import { accountToForm, buildCreateAccount, buildUpdateAccount, emptyAccountForm } from '../validation/accountForm'
import { useWorkspace } from '../workspace/useWorkspace'
import { AccountForm } from './AccountForm'

/** `/contas/nova`: cria uma conta no workspace atual e volta à lista com o aviso. */
export function NewAccountPage() {
  const { current } = useWorkspace()
  const navigate = useNavigate()

  return (
    <WriteGate backTo="/contas" backLabel="Voltar às contas">
      <PageHeader title="Criar conta" />
      <Card>
        {current ? (
          <AccountForm
            mode="create"
            initial={emptyAccountForm()}
            submitLabel="Criar conta"
            cancelTo="/contas"
            onSubmit={async (values) => {
              await createAccount(current.id, buildCreateAccount(values))
              void navigate('/contas', { state: { notice: 'Conta criada.' } })
            }}
          />
        ) : null}
      </Card>
    </WriteGate>
  )
}

/** `/contas/:id`: edita nome, saldo inicial e dados do cartão (o tipo não muda). Uma conta arquivada pede para ser restaurada antes. */
export function EditAccountPage() {
  const { current } = useWorkspace()
  const { id = '' } = useParams()

  return (
    <WriteGate backTo="/contas" backLabel="Voltar às contas">
      {current ? <EditAccount workspaceId={current.id} accountId={id} /> : null}
    </WriteGate>
  )
}

function EditAccount({ workspaceId, accountId }: { workspaceId: string; accountId: string }) {
  const navigate = useNavigate()
  const resource = useResource(`account:${workspaceId}:${accountId}`, () => getAccount(workspaceId, accountId))

  if (resource.status === 'loading') return <LoadingNote>Carregando conta…</LoadingNote>

  if (resource.status === 'error') {
    if (resource.error instanceof ApiError && resource.error.status === 404) {
      return <MissingItem title="Conta não encontrada" backTo="/contas" backLabel="Voltar às contas" />
    }
    return <ErrorNotice onRetry={resource.reload}>Não foi possível carregar a conta. Tente de novo.</ErrorNotice>
  }

  const account = resource.data
  if (account.archived) {
    return (
      <>
        <PageHeader title={account.name} />
        <Card title="Conta arquivada">
          <div className="stack">
            <p className="text-body">Restaure a conta antes de editá-la. Pela lista, em "Mostrar arquivadas", escolha "Restaurar".</p>
            <div>
              <ButtonLink to="/contas">Voltar às contas</ButtonLink>
            </div>
          </div>
        </Card>
      </>
    )
  }

  return (
    <>
      <PageHeader title="Editar conta" />
      <Card>
        <AccountForm
          key={account.id}
          mode="edit"
          initial={accountToForm(account)}
          submitLabel="Salvar"
          cancelTo="/contas"
          onSubmit={async (values) => {
            try {
              await updateAccount(workspaceId, account.id, buildUpdateAccount(values))
            } catch (error) {
              // A conta deixou de existir (outro aparelho): volta à lista, que se atualiza, em vez de ficar num formulário sem destino.
              if (error instanceof ApiError && error.status === 404) {
                void navigate('/contas', { state: { notice: FAILURE_MESSAGES.notFound } })
                return
              }
              throw error
            }
            void navigate('/contas', { state: { notice: 'Conta salva.' } })
          }}
        />
      </Card>
    </>
  )
}
