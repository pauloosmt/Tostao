import { useState } from 'react'
import { copyPeriodBudgets, type BudgetPeriodKind } from '../api/budgets'
import { Alert, Button, Field } from '../design-system/src'
import { describeFailure } from '../forms/apiErrors'
import { useSubmit } from '../forms/useSubmit'
import { ErrorNotice } from '../ui/ErrorNotice'
import { SheetDialog } from '../ui/SheetDialog'
import { copySummary } from './copySummary'

const MAX = { month: 24, week: 52 } as const
const FIELD_ID = 'budget-copy-months'

export interface CopySheetProps {
  workspaceId: string
  kind: BudgetPeriodKind
  /** O período de onde se copia: `AAAA-MM` ou `AAAA-Www`. */
  period: string
  periodLabel: string
  onClose: () => void
  onCopied: (notice: string) => void
}

/** Copia os limites do mês para os próximos (1 a 24), só onde ainda não há limite. */
export function CopySheet({ workspaceId, kind, period, periodLabel, onClose, onCopied }: CopySheetProps) {
  const [months, setMonths] = useState('1')
  const max = MAX[kind]
  const unit = kind === 'month' ? { next: 'meses', how: 'Quantos meses' } : { next: 'semanas', how: 'Quantas semanas' }
  const [error, setError] = useState<string | undefined>()
  const [problem, setProblem] = useState<{ text: string; retry: boolean } | null>(null)
  const { submitting, run } = useSubmit()

  async function submit() {
    setProblem(null)
    const count = Number(months)
    if (months.trim() === '' || !Number.isInteger(count) || count < 1 || count > max) {
      setError(`Informe de 1 a ${max} ${unit.next}.`)
      document.getElementById(FIELD_ID)?.focus()
      return
    }
    setError(undefined)
    await run(async () => {
      try {
        onCopied(copySummary(await copyPeriodBudgets(workspaceId, kind, { from: period, count })))
      } catch (failure) {
        const described = describeFailure(failure)
        setProblem({ text: described.general ?? 'Não foi possível copiar.', retry: described.retry })
      }
    })
  }

  return (
    <SheetDialog
      title={`Copiar para as próximas ${unit.next}`}
      onClose={onClose}
      onSubmit={() => void submit()}
      initialFocusId={FIELD_ID}
      footer={
        <Button type="submit" size="lg" full disabled={submitting}>
          {submitting ? 'Copiando…' : 'Copiar limites'}
        </Button>
      }
    >
      {problem ? problem.retry ? <ErrorNotice onRetry={() => void submit()}>{problem.text}</ErrorNotice> : <Alert tone="danger">{problem.text}</Alert> : null}
      <p className="text-body">
        Os limites de {periodLabel} são copiados para as {unit.next} seguintes, só onde ainda não há limite.
      </p>
      <Field id={FIELD_ID} label={unit.how} type="number" inputMode="numeric" min={1} max={max} value={months} onChange={(event) => setMonths(event.target.value)} error={error} />
    </SheetDialog>
  )
}
