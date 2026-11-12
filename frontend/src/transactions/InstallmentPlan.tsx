import { useState } from 'react'
import { useNavigate } from 'react-router'
import { cancelInstallments, getInstallmentPlan } from '../api/installments'
import type { Transaction } from '../api/types'
import { useResource } from '../data/useResource'
import { formatDate } from '../dates/dates'
import { Alert, Badge, Button, formatBRL } from '../design-system/src'
import { describeFailure } from '../forms/apiErrors'
import { useSubmit } from '../forms/useSubmit'
import { ConfirmDialog } from '../ui/ConfirmDialog'
import { ErrorNotice } from '../ui/ErrorNotice'
import { LoadingNote } from '../ui/LoadingNote'

const BACK = '/lancamentos'

export interface InstallmentPlanProps {
  workspaceId: string
  groupId: string
  /** Quem pode escrever pode cancelar as parcelas pendentes. */
  canWrite: boolean
  /** Avisa que as parcelas mudaram, para a lista atrás da folha se atualizar. */
  onChanged: () => void
}

const stateOf = (tx: Transaction) => (tx.status === 'PENDING' ? 'Pendente' : 'Efetivada')

/** O plano de uma compra parcelada: o total, o que já foi efetivado e o que falta, as parcelas e (para quem escreve) o cancelamento das pendentes. */
export function InstallmentPlan({ workspaceId, groupId, canWrite, onChanged }: InstallmentPlanProps) {
  const navigate = useNavigate()
  const { submitting, run } = useSubmit()
  const [confirming, setConfirming] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const plan = useResource(`installment-plan:${workspaceId}:${groupId}`, () => getInstallmentPlan(workspaceId, groupId))

  if (plan.status === 'loading') return <LoadingNote>Carregando o plano de parcelas…</LoadingNote>
  if (plan.status === 'error') return <ErrorNotice onRetry={plan.reload}>Não foi possível carregar o plano de parcelas. Tente de novo.</ErrorNotice>

  const { data } = plan
  const pending = data.installments.filter((tx) => tx.status === 'PENDING').length

  async function cancel() {
    await run(async () => {
      setProblem(null)
      try {
        const { cancelled } = await cancelInstallments(workspaceId, groupId)
        onChanged()
        void navigate(BACK, { replace: true, state: { notice: `${cancelled} ${cancelled === 1 ? 'parcela cancelada' : 'parcelas canceladas'}.` } })
      } catch (error) {
        setProblem(describeFailure(error).general ?? null)
      } finally {
        setConfirming(false)
      }
    })
  }

  return (
    <section className="tx-sheet__section" aria-label="Compra parcelada">
      <p className="text-label">Compra parcelada</p>
      {problem ? <Alert tone="danger">{problem}</Alert> : null}
      <p className="text-body-sm muted">
        {data.description}: {data.installmentCount} parcelas. Total {formatBRL(data.totalCents)} · efetivado {formatBRL(data.clearedCents)} · pendente {formatBRL(data.pendingCents)}.
      </p>
      <ul className="installment-list" aria-label="Parcelas">
        {data.installments.map((tx) => (
          <li key={tx.id}>
            <span className="text-body-sm">
              {tx.installmentNumber}/{data.installmentCount} · {formatDate(tx.occurredOn)}
            </span>
            <span className="text-body-sm tx-num">{formatBRL(Math.abs(tx.amountCents))}</span>
            <Badge tone={tx.status === 'PENDING' ? 'warning' : 'neutral'}>{stateOf(tx)}</Badge>
          </li>
        ))}
      </ul>
      {canWrite && pending > 0 ? (
        <div>
          <Button variant="danger-quiet" disabled={submitting} onClick={() => setConfirming(true)}>
            Cancelar parcelas pendentes
          </Button>
        </div>
      ) : null}

      <ConfirmDialog
        open={confirming}
        title="Cancelar as parcelas pendentes?"
        confirmLabel="Cancelar parcelas"
        cancelLabel="Voltar"
        busy={submitting}
        onConfirm={() => void cancel()}
        onCancel={() => setConfirming(false)}
      >
        {pending === 1 ? 'A parcela pendente será excluída.' : `As ${pending} parcelas pendentes serão excluídas.`} As efetivadas continuam.
      </ConfirmDialog>
    </section>
  )
}
