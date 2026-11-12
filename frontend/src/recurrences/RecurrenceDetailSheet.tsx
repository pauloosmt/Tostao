import { useState } from 'react'
import { Navigate, useNavigate, useParams } from 'react-router'
import { ApiError } from '../api/errors'
import { endRecurrence, getRecurrence } from '../api/recurrences'
import type { RecurrenceDetail } from '../api/types'
import { useResource } from '../data/useResource'
import { formatDate } from '../dates/dates'
import { Alert, Badge, Button, Money, formatBRL } from '../design-system/src'
import { FAILURE_MESSAGES, describeFailure } from '../forms/apiErrors'
import { useSubmit } from '../forms/useSubmit'
import { usePermissions } from '../permissions/usePermissions'
import { ConfirmDialog } from '../ui/ConfirmDialog'
import { ErrorNotice } from '../ui/ErrorNotice'
import { LoadingNote } from '../ui/LoadingNote'
import { SheetDialog } from '../ui/SheetDialog'
import type { ReferenceData } from '../transactions/reference'
import { frequencyWord } from '../validation/recurrenceForm'
import { useSheetContext } from './sheetContext'

const BACK = '/recorrencias'
const KIND = { INCOME: 'income', EXPENSE: 'expense', TRANSFER: 'transfer' } as const
const TYPE_WORD = { INCOME: 'Receita', EXPENSE: 'Despesa', TRANSFER: 'Transferência' } as const

/** `/recorrencias/:id`: a recorrência com as ocorrências e, para quem escreve, "Encerrar recorrência". */
export function RecurrenceDetailSheet() {
  const { workspaceId, reference, changed } = useSheetContext()
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const resource = useResource(`recurrence:${workspaceId}:${id}`, () => getRecurrence(workspaceId, id))
  const close = () => void navigate(BACK, { replace: true })

  if (resource.status === 'error' && resource.error instanceof ApiError && resource.error.status === 404) {
    return <Navigate to={BACK} replace state={{ notice: FAILURE_MESSAGES.notFound }} />
  }
  if (resource.status === 'error') {
    return (
      <SheetDialog title="Recorrência" onClose={close}>
        <ErrorNotice onRetry={resource.reload}>Não foi possível carregar a recorrência. Tente de novo.</ErrorNotice>
      </SheetDialog>
    )
  }
  if (resource.status === 'loading') {
    return (
      <SheetDialog title="Recorrência" onClose={close}>
        <LoadingNote>Carregando recorrência…</LoadingNote>
      </SheetDialog>
    )
  }
  return <Detail workspaceId={workspaceId} data={resource.data} reference={reference} changed={changed} onClose={close} />
}

interface DetailProps {
  workspaceId: string
  data: RecurrenceDetail
  reference: ReferenceData
  changed: () => void
  onClose: () => void
}

function Detail({ workspaceId, data, reference, changed, onClose }: DetailProps) {
  const { canWrite } = usePermissions()
  const navigate = useNavigate()
  const { submitting, run } = useSubmit()
  const [confirming, setConfirming] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const { names } = reference
  const transfer = data.type === 'TRANSFER'
  const pending = data.occurrences.filter((tx) => tx.status === 'PENDING').length

  async function end() {
    await run(async () => {
      setProblem(null)
      try {
        const { cancelled } = await endRecurrence(workspaceId, data.id)
        changed()
        void navigate(BACK, { replace: true, state: { notice: `Recorrência encerrada. ${cancelled} ${cancelled === 1 ? 'ocorrência cancelada' : 'ocorrências canceladas'}.` } })
      } catch (error) {
        setProblem(describeFailure(error).general ?? null)
      } finally {
        setConfirming(false)
      }
    })
  }

  return (
    <SheetDialog title={data.description} onClose={onClose}>
      {problem ? <Alert tone="danger">{problem}</Alert> : null}
      <div className="stack stack--tight">
        <Money cents={data.amountCents} kind={KIND[data.type] ?? 'expense'} size="lg" />
        <p className="text-body-sm muted">
          {TYPE_WORD[data.type] ?? 'Lançamento'} · {frequencyWord(data.frequency)} · começa em {formatDate(data.startOn)} · {data.endOn ? `termina em ${formatDate(data.endOn)}` : 'sem fim'}
        </p>
        <p className="text-body-sm muted">
          {transfer ? `De ${names.accountName(data.accountId)} para ${names.accountName(data.destinationAccountId ?? '')}` : `${names.accountName(data.accountId)} · ${(data.categoryId ? names.categoryName(data.categoryId) : null) ?? 'Sem categoria'}`}
        </p>
        <div className="chips">
          {data.ended ? <Badge tone="neutral">Encerrada</Badge> : null}
          {data.autoClear ? <Badge tone="brand">Efetiva sozinha</Badge> : null}
        </div>
        {data.notes ? <p className="text-body">{data.notes}</p> : null}
        <p className="text-body-sm muted">{data.ended ? 'Recorrência encerrada.' : data.nextOccurrenceOn ? `Próxima: ${formatDate(data.nextOccurrenceOn)}` : 'Sem próximas ocorrências.'}</p>
      </div>

      <section className="tx-sheet__section" aria-label="Ocorrências">
        <p className="text-label">Ocorrências</p>
        <p className="text-body-sm muted">
          Total {formatBRL(data.totalCents)} · efetivado {formatBRL(data.clearedCents)} · pendente {formatBRL(data.pendingCents)}
        </p>
        {data.occurrences.length === 0 ? (
          <p className="text-body-sm muted">Nenhuma ocorrência.</p>
        ) : (
          <ul className="installment-list" aria-label="Ocorrências da recorrência">
            {data.occurrences.map((tx) => (
              <li key={tx.id}>
                <span className="text-body-sm">{formatDate(tx.occurredOn)}</span>
                <span className="text-body-sm tx-num">{formatBRL(Math.abs(tx.amountCents))}</span>
                <Badge tone={tx.status === 'PENDING' ? 'warning' : 'neutral'}>{tx.status === 'PENDING' ? 'Pendente' : 'Efetivada'}</Badge>
              </li>
            ))}
          </ul>
        )}
      </section>

      {canWrite && !data.ended ? (
        <div>
          <Button variant="danger-quiet" disabled={submitting} onClick={() => setConfirming(true)}>
            Encerrar recorrência
          </Button>
        </div>
      ) : null}

      <ConfirmDialog
        open={confirming}
        title="Encerrar esta recorrência?"
        confirmLabel="Encerrar"
        cancelLabel="Voltar"
        busy={submitting}
        onConfirm={() => void end()}
        onCancel={() => setConfirming(false)}
      >
        {pending === 0 ? 'Não há ocorrências pendentes para excluir.' : pending === 1 ? 'A ocorrência pendente será excluída.' : `As ${pending} ocorrências pendentes serão excluídas.`} As que já ocorreram continuam, e a recorrência não cria mais nenhuma.
      </ConfirmDialog>
    </SheetDialog>
  )
}
