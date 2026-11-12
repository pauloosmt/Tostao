// O formulário de recorrência: reaproveita as regras do lançamento (valor, data de início, descrição, contas, categoria, observações) e acrescenta a
// frequência, o fim e "efetivar sozinhas". As regras espelham a API (a API é a autoridade). Tudo puro.
import type { CreateRecurrence, RecurrenceFrequency, TransactionType } from '../api/types'
import { toISO, todayISO, validateDateText } from '../dates/dates'
import { parseReais } from '../money/reais'
import type { FieldMessages } from './account'
import { emptyTransactionForm, validateTransactionForm, type TransactionField, type TransactionFormValues } from './transactionForm'

export type RecurrenceField = TransactionField | 'frequency' | 'endOn'
/** A ordem em que os campos aparecem na folha (o foco vai ao primeiro com erro). */
export const RECURRENCE_FIELD_ORDER: readonly RecurrenceField[] = ['type', 'amount', 'frequency', 'date', 'endOn', 'description', 'account', 'destination', 'category', 'notes']

/** Do nome do campo na API para o campo da folha (o início da recorrência é o campo "date"). */
export const RECURRENCE_API_FIELDS: Record<string, RecurrenceField> = {
  type: 'type',
  amountCents: 'amount',
  frequency: 'frequency',
  startOn: 'date',
  endOn: 'endOn',
  description: 'description',
  notes: 'notes',
  accountId: 'account',
  destinationAccountId: 'destination',
  categoryId: 'category',
}

export const FREQUENCY_OPTIONS: readonly { value: RecurrenceFrequency; label: string }[] = [
  { value: 'WEEKLY', label: 'Toda semana' },
  { value: 'MONTHLY', label: 'Todo mês' },
  { value: 'YEARLY', label: 'Todo ano' },
]

export const frequencyWord = (frequency: string): string => FREQUENCY_OPTIONS.find((option) => option.value === frequency)?.label ?? 'Repete'

/** O início pode ficar até este número de dias no passado (as ocorrências passadas nascem de uma vez). */
export const MAX_DAYS_BACK = 366

/** O que a pessoa digitou: os campos do lançamento (`date` é o início) mais a frequência, o fim (vazio: sem fim) e "efetivar sozinhas". */
export interface RecurrenceFormValues extends TransactionFormValues {
  frequency: RecurrenceFrequency
  /** `AAAA-MM-DD`, ou vazio para uma recorrência sem fim. */
  endOn: string
  autoClear: boolean
}

export const emptyRecurrenceForm = (type: TransactionType = 'EXPENSE', today: string = todayISO()): RecurrenceFormValues => ({
  ...emptyTransactionForm(type, today),
  frequency: 'MONTHLY',
  endOn: '',
  autoClear: false,
})

/** `AAAA-MM-DD` menos `days` dias (em componentes locais, sem fuso). */
export function daysBefore(iso: string, days: number): string {
  const [year, month, day] = iso.split('-').map(Number)
  const date = new Date(year, month - 1, day - days)
  return toISO(date.getFullYear(), date.getMonth() + 1, date.getDate())
}

/** As mensagens de cada campo com problema (vazio = tudo certo). */
export function validateRecurrenceForm(values: RecurrenceFormValues, today: string = todayISO()): FieldMessages<RecurrenceField> {
  const messages: FieldMessages<RecurrenceField> = { ...validateTransactionForm(values, 'create') }
  const start = values.date.trim()
  if (messages.date === undefined && start < daysBefore(today, MAX_DAYS_BACK)) messages.date = `Use uma data de até ${MAX_DAYS_BACK} dias atrás.`
  const end = values.endOn.trim()
  if (end !== '') {
    const problem = validateDateText(end)
    if (problem) messages.endOn = problem
    else if (messages.date === undefined && end < start) messages.endOn = 'O fim não pode ser antes do início.'
  }
  return messages
}

/**
 * O corpo do `POST`: valor positivo em centavos (o sinal vem do tipo); a transferência envia o destino e não a categoria, a receita e a despesa
 * o contrário; o fim só quando há; "efetivar sozinhas" só quando ligado. (Chamar só com o formulário já validado.)
 */
export function buildCreateRecurrence(values: RecurrenceFormValues): CreateRecurrence {
  const type = values.type as TransactionType
  const body: CreateRecurrence = {
    accountId: values.accountId,
    type,
    amountCents: (parseReais(values.amount) as { cents: number }).cents,
    description: values.description.trim(),
    frequency: values.frequency,
    startOn: values.date.trim(),
  }
  if (type === 'TRANSFER') body.destinationAccountId = values.destinationAccountId
  else body.categoryId = values.categoryId
  if (values.notes.trim() !== '') body.notes = values.notes.trim()
  if (values.endOn.trim() !== '') body.endOn = values.endOn.trim()
  if (values.autoClear) body.autoClear = true
  return body
}
