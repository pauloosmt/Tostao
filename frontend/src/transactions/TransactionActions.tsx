import { useState } from 'react'
import { useNavigate } from 'react-router'
import { clearTransaction, deleteTransaction } from '../api/transactions'
import type { Transaction } from '../api/types'
import { Alert, Button } from '../design-system/src'
import { describeFailure } from '../forms/apiErrors'
import { useSubmit } from '../forms/useSubmit'
import { ConfirmDialog } from '../ui/ConfirmDialog'
import { deleteText } from './rows'

const BACK = '/lancamentos'

export interface TransactionActionsProps {
  workspaceId: string
  transaction: Transaction
  /** Avisa que o lançamento mudou, para a lista atrás da folha se atualizar. */
  onChanged: () => void
}

/**
 * As ações sobre um lançamento já registrado, para quem altera: "Efetivar" (só no pendente) e "Excluir" (com confirmação). Cada uma roda uma
 * por vez; o sucesso volta à lista com o aviso; a falha deixa o lançamento como estava e explica; um 404 (excluído em outro aparelho) volta à
 * lista atualizada.
 */
export function TransactionActions({ workspaceId, transaction, onChanged }: TransactionActionsProps) {
  const navigate = useNavigate()
  const { submitting, run } = useSubmit()
  const [problem, setProblem] = useState<string | null>(null)
  const [confirming, setConfirming] = useState(false)
  const pending = transaction.status === 'PENDING'
  const transfer = transaction.type === 'TRANSFER'

  async function perform(action: () => Promise<unknown>, notice: string) {
    await run(async () => {
      setProblem(null)
      try {
        await action()
        onChanged()
        void navigate(BACK, { replace: true, state: { notice } })
      } catch (error) {
        const failure = describeFailure(error)
        if (failure.kind === 'notFound') {
          onChanged()
          void navigate(BACK, { replace: true, state: { notice: failure.general } })
          return
        }
        setProblem(failure.general ?? null)
      } finally {
        setConfirming(false)
      }
    })
  }

  return (
    <section className="tx-sheet__section" aria-label="Ações">
      {problem ? <Alert tone="danger">{problem}</Alert> : null}
      {pending ? (
        <p className="text-body-sm muted">
          {transfer ? 'Efetivar passa as duas pontas da transferência a contar no saldo das contas.' : 'Efetivar passa o lançamento a contar no saldo da conta.'}
        </p>
      ) : null}
      <div className="profile-actions">
        {pending ? (
          <Button variant="secondary" disabled={submitting} onClick={() => void perform(() => clearTransaction(workspaceId, transaction.id), 'Lançamento efetivado.')}>
            {submitting && !confirming ? 'Efetivando…' : 'Efetivar'}
          </Button>
        ) : null}
        <Button variant="danger-quiet" disabled={submitting} onClick={() => setConfirming(true)}>
          Excluir
        </Button>
      </div>

      <ConfirmDialog
        open={confirming}
        title={transfer ? 'Excluir esta transferência?' : 'Excluir este lançamento?'}
        confirmLabel="Excluir"
        busy={submitting}
        onConfirm={() => void perform(() => deleteTransaction(workspaceId, transaction.id), 'Lançamento excluído.')}
        onCancel={() => setConfirming(false)}
      >
        {deleteText(transaction)}
      </ConfirmDialog>
    </section>
  )
}
