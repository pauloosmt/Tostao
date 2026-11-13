import { useState, type ReactNode } from 'react'
import type { TransactionType } from '../api/types'
import { Alert, Button, CoinPicker, Field, Icon, OptionTile, type IconName } from '../design-system/src'
import { formatDate, previousDay, todayISO } from '../dates/dates'
import { describeFailure, type Failure } from '../forms/apiErrors'
import { useSubmit } from '../forms/useSubmit'
import { firstInvalid, type FieldMessages } from '../validation/account'
import { describeSplit, installmentDates, parseInstallmentCount, validateInstallments, MAX_INSTALLMENTS, MIN_INSTALLMENTS } from '../validation/installmentForm'
import { parseReais } from '../money/reais'
import {
  NOTES_MAX,
  TRANSACTION_API_FIELDS,
  TRANSACTION_TYPE_OPTIONS,
  validateTransactionForm,
  withType,
  type TransactionField,
  type TransactionFormValues,
} from '../validation/transactionForm'
import { AmountField } from '../ui/AmountField'
import { DateField } from '../ui/DateField'
import { CheckboxField } from '../ui/CheckboxField'
import { ErrorNotice } from '../ui/ErrorNotice'
import { SelectField } from '../ui/SelectField'
import { SheetDialog } from '../ui/SheetDialog'
import { TextAreaField } from '../ui/TextAreaField'
import type { ReferenceData } from './reference'

/** Os campos da folha: os do lançamento e o número de parcelas (só na compra parcelada). */
type Field = TransactionField | 'count'
/** A ordem em que aparecem (o foco vai ao primeiro com erro). */
const FORM_ORDER: readonly Field[] = ['type', 'amount', 'count', 'date', 'description', 'account', 'destination', 'category', 'notes']
/** Do campo da API da compra parcelada para o da folha. */
const API_FIELDS: Record<string, Field> = { ...TRANSACTION_API_FIELDS, totalCents: 'amount', installmentCount: 'count', firstOccurredOn: 'date' }

const FIELD_ID: Record<Field, string> = {
  count: 'tx-installments',
  type: 'tx-type',
  amount: 'tx-amount',
  date: 'tx-date',
  description: 'tx-description',
  account: 'tx-account',
  destination: 'tx-destination',
  category: 'tx-category',
  notes: 'tx-notes',
}

function focusField(field: Field) {
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
  /** Envia os valores já validados (e, na compra parcelada, o número de parcelas); falha com o erro da API (ou de rede), que o formulário mostra. Em caso de sucesso, quem chama fecha a folha. */
  onSubmit: (values: TransactionFormValues, plan?: { count: number }) => Promise<void>
  onClose: () => void
  /** Depois dos campos (as ações do lançamento já registrado). */
  after?: ReactNode
}

/** O formulário de receita, despesa e transferência, dentro da folha. As regras vêm de `validation/transactionForm.ts`. */
export function TransactionForm({ mode, initial, reference, title, submitLabel, onSubmit, onClose, after }: TransactionFormProps) {
  const [values, setValues] = useState(initial)
  const [errors, setErrors] = useState<FieldMessages<Field>>({})
  const [failure, setFailure] = useState<Failure<Field> | null>(null)
  // A compra parcelada (só em despesa nova): o número de parcelas fica aqui, fora dos valores do lançamento.
  const [parcelar, setParcelar] = useState(false)
  const [count, setCount] = useState('2')
  const { submitting, run } = useSubmit()

  const set = <K extends keyof TransactionFormValues>(field: K, value: TransactionFormValues[K]) => setValues((current) => ({ ...current, [field]: value }))
  const type = values.type === '' ? 'EXPENSE' : values.type
  const transfer = type === 'TRANSFER'
  const installments = mode === 'create' && type === 'EXPENSE' && parcelar
  const total = parseReais(values.amount)
  const planCount = parseInstallmentCount(count)
  const kind = type === 'INCOME' ? 'INCOME' : 'EXPENSE'
  const today = todayISO()
  const categories = reference.categoryOptions(kind, initial.categoryId)
  const chosenCategory = categories.find((option) => option.value === values.categoryId)

  async function submit() {
    const found: FieldMessages<Field> = validateTransactionForm(values, mode)
    // As regras da compra parcelada valem junto com as do lançamento (o erro do lançamento, no mesmo campo, vem primeiro).
    if (installments) for (const [field, message] of Object.entries(validateInstallments(values, count))) found[field as Field] ??= message
    setErrors(found)
    setFailure(null)
    const first = firstInvalid(found, FORM_ORDER)
    if (first) {
      focusField(first)
      return
    }
    await run(async () => {
      try {
        await onSubmit(values, installments && planCount !== undefined ? { count: planCount } : undefined)
      } catch (error) {
        const described = describeFailure<Field>(error, { fieldMap: API_FIELDS })
        setErrors(described.fields)
        setFailure(described)
        const firstFromApi = firstInvalid(described.fields, FORM_ORDER)
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
              onClick={() => {
                setValues((current) => withType(current, option.value as TransactionType, reference.categoryFits))
                if (option.value !== 'EXPENSE') setParcelar(false)
              }}
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

      <AmountField id={FIELD_ID.amount} kind={AMOUNT_KIND[type]} label={installments ? 'Valor total' : 'Valor'} value={values.amount} onChange={(text) => set('amount', text)} error={errors.amount} />

      {mode === 'create' && type === 'EXPENSE' ? <CheckboxField label="Parcelar a compra" checked={parcelar} onChange={(event) => setParcelar(event.target.checked)} /> : null}
      {installments ? (
        <div className="tx-sheet__section">
          <Field
            id={FIELD_ID.count}
            label="Número de parcelas"
            inputMode="numeric"
            autoComplete="off"
            hint={`De ${MIN_INSTALLMENTS} a ${MAX_INSTALLMENTS}, uma por mês.`}
            value={count}
            onChange={(event) => setCount(event.target.value)}
            error={errors.count}
          />
          {planCount !== undefined && 'cents' in total && total.cents >= planCount ? (
            <p className="text-body-sm muted">
              {describeSplit(total.cents, planCount)}. De {formatDate(values.date)} a {formatDate(installmentDates(values.date, planCount)[planCount - 1])}.
            </p>
          ) : null}
        </div>
      ) : null}

      <div className="tx-sheet__section">
        <DateField id={FIELD_ID.date} label={installments ? 'Data da primeira parcela' : 'Data'} value={values.date} onChange={(iso) => set('date', iso)} error={errors.date} />
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
              options={categories.map((option) => ({ name: option.label, color: option.color ?? '#8c877a', icon: option.icon }))}
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

      <CheckboxField label={installments ? 'Primeira parcela pendente (ainda não aconteceu)' : 'Pendente (ainda não aconteceu)'} checked={values.pending} onChange={(event) => set('pending', event.target.checked)} />

      {after}
    </SheetDialog>
  )
}
