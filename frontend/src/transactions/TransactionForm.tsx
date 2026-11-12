import { useState, type ReactNode } from 'react'
import type { TransactionType } from '../api/types'
import { Alert, Button, CoinPicker, Field, Icon, OptionTile, type IconName } from '../design-system/src'
import { previousDay, todayISO } from '../dates/dates'
import { describeFailure, type Failure } from '../forms/apiErrors'
import { useSubmit } from '../forms/useSubmit'
import { firstInvalid, type FieldMessages } from '../validation/account'
import {
  NOTES_MAX,
  TRANSACTION_API_FIELDS,
  TRANSACTION_FIELD_ORDER,
  TRANSACTION_TYPE_OPTIONS,
  validateTransactionForm,
  withType,
  type TransactionField,
  type TransactionFormValues,
} from '../validation/transactionForm'
import { AmountField } from '../ui/AmountField'
import { CheckboxField } from '../ui/CheckboxField'
import { ErrorNotice } from '../ui/ErrorNotice'
import { SelectField } from '../ui/SelectField'
import { SheetDialog } from '../ui/SheetDialog'
import { TextAreaField } from '../ui/TextAreaField'
import type { ReferenceData } from './reference'

const FIELD_ID: Record<TransactionField, string> = {
  type: 'tx-type',
  amount: 'tx-amount',
  date: 'tx-date',
  description: 'tx-description',
  account: 'tx-account',
  destination: 'tx-destination',
  category: 'tx-category',
  notes: 'tx-notes',
}

function focusField(field: TransactionField) {
  // A categoria é um grupo de moedas: o foco vai à primeira.
  const target = field === 'category' ? document.querySelector<HTMLElement>('[role="radiogroup"][aria-label="Categoria"] button') : document.getElementById(FIELD_ID[field])
  target?.focus()
}

const TYPE_ICON: Record<string, IconName> = { EXPENSE: 'arrowUp', INCOME: 'arrowDown', TRANSFER: 'swap' }
const AMOUNT_KIND = { EXPENSE: 'expense', INCOME: 'income', TRANSFER: 'transfer' } as const

export interface TransactionFormProps {
  mode: 'create' | 'edit'
  initial: TransactionFormValues
  reference: ReferenceData
  /** O título da folha ("Novo lançamento"). */
  title: string
  submitLabel: string
  /** Envia os valores já validados; falha com o erro da API (ou de rede), que o formulário mostra. Em caso de sucesso, quem chama fecha a folha. */
  onSubmit: (values: TransactionFormValues) => Promise<void>
  onClose: () => void
  /** Depois dos campos (as ações do lançamento já registrado). */
  after?: ReactNode
}

/** O formulário de receita, despesa e transferência, dentro da folha. As regras vêm de `validation/transactionForm.ts`. */
export function TransactionForm({ mode, initial, reference, title, submitLabel, onSubmit, onClose, after }: TransactionFormProps) {
  const [values, setValues] = useState(initial)
  const [errors, setErrors] = useState<FieldMessages<TransactionField>>({})
  const [failure, setFailure] = useState<Failure<TransactionField> | null>(null)
  const { submitting, run } = useSubmit()

  const set = <K extends keyof TransactionFormValues>(field: K, value: TransactionFormValues[K]) => setValues((current) => ({ ...current, [field]: value }))
  const type = values.type === '' ? 'EXPENSE' : values.type
  const transfer = type === 'TRANSFER'
  const kind = type === 'INCOME' ? 'INCOME' : 'EXPENSE'
  const today = todayISO()
  const categories = reference.categoryOptions(kind, initial.categoryId)
  const chosenCategory = categories.find((option) => option.value === values.categoryId)

  async function submit() {
    const found = validateTransactionForm(values, mode)
    setErrors(found)
    setFailure(null)
    const first = firstInvalid(found, TRANSACTION_FIELD_ORDER)
    if (first) {
      focusField(first)
      return
    }
    await run(async () => {
      try {
        await onSubmit(values)
      } catch (error) {
        const described = describeFailure<TransactionField>(error, { fieldMap: TRANSACTION_API_FIELDS })
        setErrors(described.fields)
        setFailure(described)
        const firstFromApi = firstInvalid(described.fields, TRANSACTION_FIELD_ORDER)
        if (firstFromApi) focusField(firstFromApi)
      }
    })
  }

  return (
    <SheetDialog
      title={title}
      onClose={onClose}
      onSubmit={() => void submit()}
      initialFocusId={FIELD_ID.amount}
      footer={
        <Button type="submit" size="lg" full disabled={submitting}>
          {submitting ? 'Salvando…' : submitLabel}
        </Button>
      }
    >
      {failure?.general ? (
        failure.retry ? <ErrorNotice onRetry={() => void submit()}>{failure.general}</ErrorNotice> : <Alert tone="danger">{failure.general}</Alert>
      ) : null}

      {mode === 'create' ? (
        <div className="tile-row" role="radiogroup" aria-label="Tipo">
          {TRANSACTION_TYPE_OPTIONS.map((option) => (
            <OptionTile
              key={option.value}
              icon={TYPE_ICON[option.value]}
              selected={type === option.value}
              onClick={() => setValues((current) => withType(current, option.value as TransactionType, reference.categoryFits))}
            >
              {option.label}
            </OptionTile>
          ))}
        </div>
      ) : (
        <p className="text-body-sm muted">
          {TRANSACTION_TYPE_OPTIONS.find((option) => option.value === type)?.label}. O tipo não pode ser mudado depois de registrado.
        </p>
      )}

      <AmountField id={FIELD_ID.amount} kind={AMOUNT_KIND[type]} value={values.amount} onChange={(text) => set('amount', text)} error={errors.amount} />

      <div className="tx-sheet__section">
        <Field id={FIELD_ID.date} label="Data" type="date" value={values.date} onChange={(event) => set('date', event.target.value)} error={errors.date} />
        <div className="tile-row tile-row--chips" role="radiogroup" aria-label="Atalhos de data">
          <OptionTile compact selected={values.date === today} onClick={() => set('date', today)}>
            Hoje
          </OptionTile>
          <OptionTile compact selected={values.date === previousDay(today)} onClick={() => set('date', previousDay(today))}>
            Ontem
          </OptionTile>
        </div>
      </div>

      <Field id={FIELD_ID.description} label="Descrição" autoComplete="off" value={values.description} onChange={(event) => set('description', event.target.value)} error={errors.description} />

      <SelectField
        id={FIELD_ID.account}
        label={transfer ? 'De' : 'Conta'}
        placeholder={transfer ? 'Escolha a conta de origem' : 'Escolha a conta'}
        options={reference.accountOptions(initial.accountId)}
        value={values.accountId}
        onChange={(event) => set('accountId', event.target.value)}
        error={errors.account}
      />

      {transfer ? (
        <SelectField
          id={FIELD_ID.destination}
          label="Para"
          placeholder="Escolha a conta de destino"
          options={reference.accountOptions(initial.destinationAccountId)}
          value={values.destinationAccountId}
          onChange={(event) => set('destinationAccountId', event.target.value)}
          error={errors.destination}
        />
      ) : (
        <div className="tx-sheet__section">
          <p className="text-label">Categoria</p>
          {categories.length === 0 ? (
            <p className="text-body-sm muted">Não há categoria ativa deste tipo. Crie uma em Categorias.</p>
          ) : (
            <CoinPicker
              label="Categoria"
              value={chosenCategory?.label}
              options={categories.map((option) => ({ name: option.label, color: option.color ?? '#8c877a' }))}
              onChange={(name) => set('categoryId', categories.find((option) => option.label === name)?.value ?? '')}
            />
          )}
          {errors.category ? (
            <p className="tx-field__msg tx-field__msg--error text-body-sm" role="alert">
              <Icon name="alert" size={16} />
              {errors.category}
            </p>
          ) : null}
        </div>
      )}

      <TextAreaField id={FIELD_ID.notes} label="Observações (opcional)" value={values.notes} max={NOTES_MAX} onChange={(event) => set('notes', event.target.value)} error={errors.notes} />

      <CheckboxField label="Pendente (ainda não aconteceu)" checked={values.pending} onChange={(event) => set('pending', event.target.checked)} />

      {after}
    </SheetDialog>
  )
}
