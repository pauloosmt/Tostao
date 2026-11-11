import { useNavigate } from 'react-router'
import { createAccount } from '../api/accounts'
import { Card } from '../design-system/src'
import { WriteGate } from '../permissions/WriteGate'
import { buildCreateAccount, emptyAccountForm } from '../validation/accountForm'
import { useWorkspace } from '../workspace/useWorkspace'
import { AccountForm } from './AccountForm'

/** `/contas/nova`: cria uma conta no workspace atual e volta à lista com o aviso. */
export function NewAccountPage() {
  const { current } = useWorkspace()
  const navigate = useNavigate()

  return (
    <WriteGate backTo="/contas" backLabel="Voltar às contas">
      <h1 className="heading-1">Criar conta</h1>
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
