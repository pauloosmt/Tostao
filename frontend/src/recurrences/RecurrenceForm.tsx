import { useState } from 'react'
import type { TransactionType } from '../api/types'
import { Alert, Button, CoinPicker, Field, Icon, OptionTile, type IconName } from '../design-system/src'
import { describeFailure, type Failure } from '../forms/apiErrors'
import { useSubmit } from '../forms/useSubmit'
import { firstInvalid, type FieldMessages } from '../validation/account'
import {
  FREQUENCY_OPTIONS,
  RECURRENCE_API_FIELDS,
  RECURRENCE_FIELD_ORDER,
  emptyRecurrenceForm,
  validateRecurrenceForm,
  type RecurrenceField,
  type RecurrenceFormValues,
} from '../validation/recurrenceForm'
import { NOTES_MAX, TRANSACTION_TYPE_OPTIONS, withType } from '../validation/transactionForm'
import { AmountField } from '../ui/AmountField'
import { DateField } from '../ui/DateField'
import { CheckboxField } from '../ui/CheckboxField'
import { ErrorNotice } from '../ui/ErrorNotice'
import { SelectField } from '../ui/SelectField'
import { SheetDialog } from '../ui/SheetDialog'
import { TextAreaField } from '../ui/TextAreaField'
import type { ReferenceData } from '../transactions/reference'

const FIELD_ID: Partial<Record<RecurrenceField, string>> = {
  amount: 'rec-amount',
  date: 'rec-start',
  endOn: 'rec-end',
  description: 'rec-description',
  account: 'rec-account',
  destination: 'rec-destination',
  notes: 'rec-notes',
}

/** O foco vai ao campo: os que são grupos de botões (tipo, frequência, categoria) recebem no primeiro botão. */
function focusField(field: RecurrenceField) {
  const group = { type: 'Tipo', frequency: 'Frequência', category: 'Categoria' }[field as 'type' | 'frequency' | 'category']
  const target = group ? document.querySelector<HTMLElement>(`[role="radiogroup"][aria-label="${group}"] button`) : document.getElementById(FIELD_ID[field] ?? '')
  target?.focus()
}

const TYPE_ICON: Record<string, IconName> = { EXPENSE: 'arrowUp', INCOME: 'arrowDown', TRANSFER: 'swap' }
const AMOUNT_KIND = { EXPENSE: 'expense', INCOME: 'income', TRANSFER: 'transfer' } as const

export interface RecurrenceFormProps {
  reference: ReferenceData
  /** Envia os valores já validados; falha com o erro da API (ou de rede), que o formulário mostra. Em caso de sucesso, quem chama fecha a folha. */
  onSubmit: (values: RecurrenceFormValues) => Promise<void>
  onClose: () => void
}

/** A folha de uma recorrência nova: o que se repete (tipo, valor, contas, categoria), de quanto em quanto tempo e de quando a quando. */
export function RecurrenceForm({ reference, onSubmit, onClose }: RecurrenceFormProps) {
  const [values, setValues] = useState<RecurrenceFormValues>(() => emptyRecurrenceForm())
  const [errors, setErrors] = useState<FieldMessages<RecurrenceField>>({})
  const [failure, setFailure] = useState<Failure<RecurrenceField> | null>(null)
  const { submitting, run } = useSubmit()

  const set = <K extends keyof RecurrenceFormValues>(field: K, value: RecurrenceFormValues[K]) => setValues((current) => ({ ...current, [field]: value }))
  const type = values.type === '' ? 'EXPENSE' : values.type
  const transfer = type === 'TRANSFER'
  const categories = reference.categoryOptions(type === 'INCOME' ? 'INCOME' : 'EXPENSE')
  const chosenCategory = categories.find((option) => option.value === values.categoryId)

  async function submit() {
    const found = validateRecurrenceForm(values)
    setErrors(found)
    setFailure(null)
    const first = firstInvalid(found, RECURRENCE_FIELD_ORDER)
    if (first) {
      focusField(first)
      return
    }
    await run(async () => {
      try {
        await onSubmit(values)
      } catch (error) {
        const described = describeFailure<RecurrenceField>(error, { fieldMap: RECURRENCE_API_FIELDS })
        setErrors(described.fields)
        setFailure(described)
        const firstFromApi = firstInvalid(described.fields, RECURRENCE_FIELD_ORDER)
        if (firstFromApi) focusField(firstFromApi)
      }
    })
  }

  return (
    <SheetDialog
      title="Nova recorrência"
      onClose={onClose}
      onSubmit={() => void submit()}
      initialFocusId={FIELD_ID.amount}
      footer={
        <Button type="submit" size="lg" full disabled={submitting}>
          {submitting ? 'Salvando…' : 'Criar recorrência'}
        </Button>
      }
    >
      {failure?.general ? (
        failure.retry ? <ErrorNotice onRetry={() => void submit()}>{failure.general}</ErrorNotice> : <Alert tone="danger">{failure.general}</Alert>
      ) : null}

      <div className="tile-row" role="radiogroup" aria-label="Tipo">
        {TRANSACTION_TYPE_OPTIONS.map((option) => (
          <OptionTile
            key={option.value}
            icon={TYPE_ICON[option.value]}
            selected={type === option.value}
            onClick={() => setValues((current) => ({ ...current, ...withType(current, option.value as TransactionType, reference.categoryFits) }))}
          >
            {option.label}
          </OptionTile>
        ))}
      </div>

      <AmountField id={FIELD_ID.amount} kind={AMOUNT_KIND[type]} value={values.amount} onChange={(text) => set('amount', text)} error={errors.amount} />

      <div className="tx-sheet__section">
        <span className="text-label">Repete</span>
        <div className="tile-row" role="radiogroup" aria-label="Frequência">
          {FREQUENCY_OPTIONS.map((option) => (
            <OptionTile key={option.value} compact selected={values.frequency === option.value} onClick={() => set('frequency', option.value)}>
              {option.label}
            </OptionTile>
          ))}
        </div>
        {errors.frequency ? <p className="tx-field__msg tx-field__msg--error text-body-sm" role="alert"><Icon name="alert" size={16} />{errors.frequency}</p> : null}
      </div>

      <div className="form-row">
        <DateField id={FIELD_ID.date} label="Começa em" value={values.date} onChange={(iso) => set('date', iso)} error={errors.date} />
        <DateField
          id={FIELD_ID.endOn}
          label="Termina em (opcional)"
          hint="Vazio: repete sem fim."
          value={values.endOn}
          onChange={(iso) => set('endOn', iso)}
          error={errors.endOn}
        />
      </div>

      <Field id={FIELD_ID.description} label="Descrição" autoComplete="off" value={values.description} onChange={(event) => set('description', event.target.value)} error={errors.description} />

      <SelectField
        id={FIELD_ID.account}
        label={transfer ? 'De' : 'Conta'}
        placeholder={transfer ? 'Escolha a conta de origem' : 'Escolha a conta'}
        options={reference.accountOptions()}
        value={values.accountId}
        onChange={(event) => set('accountId', event.target.value)}
        error={errors.account}
      />

      {transfer ? (
        <SelectField
          id={FIELD_ID.destination}
          label="Para"
          placeholder="Escolha a conta de destino"
          options={reference.accountOptions()}
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
              options={categories.map((option) => ({ name: option.label, color: option.color ?? '#8c877a', icon: option.icon }))}
              onChange={(name) => set('categoryId', categories.find((option) => option.label === name)?.value ?? '')}
            />
          )}
          {errors.category ? <p className="tx-field__msg tx-field__msg--error text-body-sm" role="alert"><Icon name="alert" size={16} />{errors.category}</p> : null}
        </div>
      )}

      <TextAreaField id={FIELD_ID.notes} label="Observações (opcional)" value={values.notes} max={NOTES_MAX} onChange={(event) => set('notes', event.target.value)} error={errors.notes} />

      <div className="tx-sheet__section">
        <CheckboxField label="Efetivar sozinhas na data" checked={values.autoClear} onChange={(event) => set('autoClear', event.target.checked)} />
        <p className="text-body-sm muted">As ocorrências nascem pendentes e passam a efetivadas no dia de cada uma.</p>
      </div>
    </SheetDialog>
  )
}
