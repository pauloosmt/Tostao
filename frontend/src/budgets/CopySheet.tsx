import { useState } from 'react'
import { copyMonthlyBudgets } from '../api/budgets'
import { monthLabel } from '../dates/months'
import { Alert, Button, Field } from '../design-system/src'
import { describeFailure } from '../forms/apiErrors'
import { useSubmit } from '../forms/useSubmit'
import { ErrorNotice } from '../ui/ErrorNotice'
import { SheetDialog } from '../ui/SheetDialog'
import { copySummary } from './copySummary'

const MAX_MONTHS = 24
const FIELD_ID = 'budget-copy-months'

export interface CopySheetProps {
  workspaceId: string
  /** O mês de onde se copia. */
  month: string
  onClose: () => void
  onCopied: (notice: string) => void
}

/** Copia os limites do mês para os próximos (1 a 24), só onde ainda não há limite. */
export function CopySheet({ workspaceId, month, onClose, onCopied }: CopySheetProps) {
  const [months, setMonths] = useState('1')
  const [error, setError] = useState<string | undefined>()
  const [problem, setProblem] = useState<{ text: string; retry: boolean } | null>(null)
  const { submitting, run } = useSubmit()

  async function submit() {
    setProblem(null)
    const count = Number(months)
    if (months.trim() === '' || !Number.isInteger(count) || count < 1 || count > MAX_MONTHS) {
      setError(`Informe de 1 a ${MAX_MONTHS} meses.`)
      document.getElementById(FIELD_ID)?.focus()
      return
    }
    setError(undefined)
    await run(async () => {
      try {
        onCopied(copySummary(await copyMonthlyBudgets(workspaceId, { fromMonth: month, months: count })))
      } catch (failure) {
        const described = describeFailure(failure)
        setProblem({ text: described.general ?? 'Não foi possível copiar.', retry: described.retry })
      }
    })
  }

  return (
    <SheetDialog
      title="Copiar para os próximos meses"
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
        Os limites de {monthLabel(month)} são copiados para os meses seguintes, só onde ainda não há limite.
      </p>
      <Field id={FIELD_ID} label="Quantos meses" type="number" inputMode="numeric" min={1} max={MAX_MONTHS} value={months} onChange={(event) => setMonths(event.target.value)} error={error} />
    </SheetDialog>
  )
}
