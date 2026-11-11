import { useState } from 'react'
import { useNavigate } from 'react-router'
import { clearTransaction } from '../api/transactions'
import type { Transaction } from '../api/types'
import { Alert, Button, Card } from '../design-system/src'
import { describeFailure } from '../forms/apiErrors'
import { useSubmit } from '../forms/useSubmit'

const BACK = '/lancamentos'

export interface TransactionActionsProps {
  workspaceId: string
  transaction: Transaction
}

/**
 * As ações sobre um lançamento já registrado, para quem altera: "Efetivar" (só no pendente). Cada uma roda uma por vez; o sucesso volta à
 * lista com o aviso; a falha deixa o lançamento como estava e explica; um 404 (excluído em outro aparelho) volta à lista atualizada.
 */
export function TransactionActions({ workspaceId, transaction }: TransactionActionsProps) {
  const navigate = useNavigate()
  const { submitting, run } = useSubmit()
  const [problem, setProblem] = useState<string | null>(null)
  const pending = transaction.status === 'PENDING'

  async function perform(action: () => Promise<unknown>, notice: string) {
    await run(async () => {
      setProblem(null)
      try {
        await action()
        void navigate(BACK, { state: { notice } })
      } catch (error) {
        const failure = describeFailure(error)
        if (failure.kind === 'notFound') {
          void navigate(BACK, { state: { notice: failure.general } })
          return
        }
        setProblem(failure.general ?? null)
      }
    })
  }

  if (!pending) return null

  return (
    <Card title="Ações">
      <div className="stack">
        {problem ? <Alert tone="danger">{problem}</Alert> : null}
        <p className="text-body-sm muted">
          {transaction.type === 'TRANSFER' ? 'Efetivar passa as duas pontas da transferência a contar no saldo das contas.' : 'Efetivar passa o lançamento a contar no saldo da conta.'}
        </p>
        <div className="profile-actions">
          <Button disabled={submitting} onClick={() => void perform(() => clearTransaction(workspaceId, transaction.id), 'Lançamento efetivado.')}>
            {submitting ? 'Efetivando…' : 'Efetivar'}
          </Button>
        </div>
      </div>
    </Card>
  )
}
