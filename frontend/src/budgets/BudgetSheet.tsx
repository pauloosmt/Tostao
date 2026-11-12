import { useState } from 'react'
import { definePeriodBudget, updatePeriodBudget, type BudgetPeriodKind } from '../api/budgets'
import type { BudgetItem } from '../api/types'
import { Alert, Button } from '../design-system/src'
import { describeFailure, type Failure } from '../forms/apiErrors'
import { useSubmit } from '../forms/useSubmit'
import { formatReaisInput, parseReais } from '../money/reais'
import { AmountField } from '../ui/AmountField'
import { ErrorNotice } from '../ui/ErrorNotice'
import { SelectField } from '../ui/SelectField'
import { SheetDialog } from '../ui/SheetDialog'

type Field = 'category' | 'amount'
const API_FIELDS: Record<string, Field> = { categoryId: 'category', amountCents: 'amount' }
const AMOUNT_ID = 'budget-amount'

export interface BudgetSheetProps {
  workspaceId: string
  kind: BudgetPeriodKind
  /** O período do limite: `AAAA-MM` ou `AAAA-Www`. */
  period: string
  /** O período por extenso, para a pessoa ver de qual é o limite. */
  periodLabel: string
  /** Sem `item`: um limite novo; com `item`: o novo valor dele (o escopo não muda). */
  item?: BudgetItem
  /** As categorias de despesa ativas, para o limite novo. */
  categories: { value: string; label: string }[]
  onClose: () => void
  /** Salvou: quem chama fecha a folha, mostra o aviso e atualiza a tela. */
  onSaved: (notice: string) => void
}

/** A folha de um limite do mês: a categoria (ou o geral) e o valor, em reais. */
export function BudgetSheet({ workspaceId, kind, period, periodLabel, item, categories, onClose, onSaved }: BudgetSheetProps) {
  const [categoryId, setCategoryId] = useState('')
  const [amount, setAmount] = useState(item ? formatReaisInput(item.amountCents) : '')
  const [errors, setErrors] = useState<Partial<Record<Field, string>>>({})
  const [failure, setFailure] = useState<Failure<Field> | null>(null)
  const { submitting, run } = useSubmit()

  async function submit() {
    setFailure(null)
    const parsed = amount.trim() === '' ? { error: 'Informe o valor do limite.' } : parseReais(amount)
    if ('error' in parsed || parsed.cents < 0) {
      setErrors({ amount: 'error' in parsed ? parsed.error : 'O limite não pode ser negativo.' })
      document.getElementById(AMOUNT_ID)?.focus()
      return
    }
    setErrors({})
    await run(async () => {
      try {
        if (item) await updatePeriodBudget(workspaceId, kind, item.id, parsed.cents)
        else await definePeriodBudget(workspaceId, kind, { period, ...(categoryId ? { categoryId } : {}), amountCents: parsed.cents })
        onSaved(item ? 'Limite atualizado.' : 'Limite salvo.')
      } catch (error) {
        const described = describeFailure<Field>(error, { fieldMap: API_FIELDS })
        setErrors(described.fields)
        setFailure(described)
      }
    })
  }

  return (
    <SheetDialog
      title={item ? 'Editar limite' : 'Novo limite'}
      onClose={onClose}
      onSubmit={() => void submit()}
      initialFocusId={AMOUNT_ID}
      footer={
        <Button type="submit" size="lg" full disabled={submitting}>
          {submitting ? 'Salvando…' : 'Salvar'}
        </Button>
      }
    >
      {failure?.general ? failure.retry ? <ErrorNotice onRetry={() => void submit()}>{failure.general}</ErrorNotice> : <Alert tone="danger">{failure.general}</Alert> : null}
      <p className="text-body-sm muted">{periodLabel}</p>
      {item ? (
        <p className="text-body">{item.categoryName ?? 'Limite geral (todas as despesas)'}</p>
      ) : (
        <SelectField
          label="Categoria"
          placeholder="Geral (todas as despesas)"
          options={categories}
          value={categoryId}
          onChange={(event) => setCategoryId(event.target.value)}
          error={errors.category}
        />
      )}
      <AmountField id={AMOUNT_ID} kind="expense" label={kind === 'month' ? 'Limite do mês' : 'Limite da semana'} value={amount} onChange={setAmount} error={errors.amount} />
    </SheetDialog>
  )
}
