import { useState, type FormEvent } from 'react'
import type { TransactionType } from '../api/types'
import { Alert, Button, Field, Segmented } from '../design-system/src'
import { describeFailure, type Failure } from '../forms/apiErrors'
import { useSubmit } from '../forms/useSubmit'
import { firstInvalid, type FieldMessages } from '../validation/account'
import {
  TRANSACTION_API_FIELDS,
  TRANSACTION_FIELD_ORDER,
  TRANSACTION_TYPE_OPTIONS,
  validateTransactionForm,
  withType,
  type TransactionField,
  type TransactionFormValues,
} from '../validation/transactionForm'
import { ButtonLink } from '../ui/ButtonLink'
import { CheckboxField } from '../ui/CheckboxField'
import { ErrorNotice } from '../ui/ErrorNotice'
import { MoneyField } from '../ui/MoneyField'
import { SelectField } from '../ui/SelectField'
import { TextAreaField } from '../ui/TextAreaField'
import { NOTES_MAX } from '../validation/transactionForm'
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
const focusField = (field: TransactionField) => document.getElementById(FIELD_ID[field])?.focus()

const TYPE_SELECT = TRANSACTION_TYPE_OPTIONS.map((option) => ({ value: option.value, label: option.label }))

export interface TransactionFormProps {
  mode: 'create' | 'edit'
  initial: TransactionFormValues
  reference: ReferenceData
  submitLabel: string
  /** Envia os valores já validados; falha com o erro da API (ou de rede), que o formulário mostra. Em caso de sucesso, quem chama navega. */
  onSubmit: (values: TransactionFormValues) => Promise<void>
  cancelTo: string
}

export function TransactionForm({ mode, initial, reference, submitLabel, onSubmit, cancelTo }: TransactionFormProps) {
  const [values, setValues] = useState(initial)
  const [errors, setErrors] = useState<FieldMessages<TransactionField>>({})
  const [failure, setFailure] = useState<Failure<TransactionField> | null>(null)
  const { submitting, run } = useSubmit()

  const set = <K extends keyof TransactionFormValues>(field: K, value: TransactionFormValues[K]) => setValues((current) => ({ ...current, [field]: value }))
  const transfer = values.type === 'TRANSFER'
  const kind = values.type === 'INCOME' ? 'INCOME' : 'EXPENSE'

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

  function onFormSubmit(event: FormEvent) {
    event.preventDefault()
    void submit()
  }

  return (
    <form className="stack" onSubmit={onFormSubmit} noValidate>
      {failure?.general ? (
        failure.retry ? <ErrorNotice onRetry={() => void submit()}>{failure.general}</ErrorNotice> : <Alert tone="danger">{failure.general}</Alert>
      ) : null}

      {mode === 'create' ? (
        <Segmented
          label="Tipo"
          value={values.type === '' ? 'EXPENSE' : values.type}
          options={TYPE_SELECT}
          onChange={(type: TransactionType) => setValues((current) => withType(current, type, reference.categoryFits))}
        />
      ) : (
        <SelectField id={FIELD_ID.type} label="Tipo" options={TYPE_SELECT} value={values.type} disabled hint="O tipo não pode ser mudado depois de registrado." onChange={() => {}} />
      )}

      <MoneyField id={FIELD_ID.amount} label="Valor" value={values.amount} onChange={(text) => set('amount', text)} error={errors.amount} />

      <Field id={FIELD_ID.date} label="Data" type="date" value={values.date} onChange={(event) => set('date', event.target.value)} error={errors.date} />

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
        <SelectField
          id={FIELD_ID.category}
          label="Categoria"
          placeholder="Escolha a categoria"
          options={reference.categoryOptions(kind, initial.categoryId)}
          value={values.categoryId}
          onChange={(event) => set('categoryId', event.target.value)}
          error={errors.category}
        />
      )}

      <TextAreaField id={FIELD_ID.notes} label="Observações (opcional)" value={values.notes} max={NOTES_MAX} onChange={(event) => set('notes', event.target.value)} error={errors.notes} />

      <CheckboxField label="Pendente (ainda não aconteceu)" checked={values.pending} onChange={(event) => set('pending', event.target.checked)} />

      <div className="form-actions">
        <ButtonLink to={cancelTo}>Cancelar</ButtonLink>
        <Button type="submit" disabled={submitting}>
          {submitting ? 'Salvando…' : submitLabel}
        </Button>
      </div>
    </form>
  )
}
