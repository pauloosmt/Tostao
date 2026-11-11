// O formulário de lançamento: as regras (que espelham a API; a API é a autoridade), a montagem do corpo da requisição e a ida e volta com o lançamento.
import { validateDateText, todayISO } from '../dates/dates'
import { MAX_CENTS, formatReaisInput, parseReais } from '../money/reais'
import type { CreateTransaction, Transaction, TransactionType, UpdateTransaction } from '../api/types'
import type { FieldMessages } from './account'

export type TransactionField = 'type' | 'amount' | 'date' | 'description' | 'account' | 'destination' | 'category' | 'notes'
/** A ordem em que os campos aparecem na tela (o foco vai ao primeiro com erro). */
export const TRANSACTION_FIELD_ORDER: readonly TransactionField[] = ['type', 'amount', 'date', 'description', 'account', 'destination', 'category', 'notes']

/** Do nome do campo na API para o campo da tela. */
export const TRANSACTION_API_FIELDS: Record<string, TransactionField> = {
  type: 'type',
  amountCents: 'amount',
  occurredOn: 'date',
  description: 'description',
  notes: 'notes',
  accountId: 'account',
  destinationAccountId: 'destination',
  categoryId: 'category',
}

export const DESCRIPTION_MAX = 200
export const NOTES_MAX = 1000

export const TRANSACTION_TYPE_OPTIONS: readonly { value: TransactionType; label: string }[] = [
  { value: 'EXPENSE', label: 'Despesa' },
  { value: 'INCOME', label: 'Receita' },
  { value: 'TRANSFER', label: 'Transferência' },
]

/** O que a pessoa digitou, tudo como texto (o valor em reais, a data como `AAAA-MM-DD`). */
export interface TransactionFormValues {
  type: TransactionType | ''
  amount: string
  date: string
  description: string
  notes: string
  /** A conta; na transferência, a de origem. */
  accountId: string
  /** Só na transferência. */
  destinationAccountId: string
  /** Só em receita e despesa. */
  categoryId: string
  pending: boolean
}

export const emptyTransactionForm = (type: TransactionType = 'EXPENSE', today: string = todayISO()): TransactionFormValues => ({
  type,
  amount: '',
  date: today,
  description: '',
  notes: '',
  accountId: '',
  destinationAccountId: '',
  categoryId: '',
  pending: false,
})

/** A origem e o destino de uma transferência, qualquer que seja a ponta (a saída tem valor negativo; a entrada, positivo). */
export function transferEnds(tx: Pick<Transaction, 'amountCents' | 'accountId' | 'counterpartAccountId'>): { origin: string; destination: string } {
  const other = tx.counterpartAccountId ?? ''
  return tx.amountCents < 0 ? { origin: tx.accountId, destination: other } : { origin: other, destination: tx.accountId }
}

/** O formulário de edição: os dados atuais do lançamento, com o valor em reais positivos (o sinal volta do tipo). */
export function transactionToForm(tx: Transaction): TransactionFormValues {
  const transfer = tx.type === 'TRANSFER'
  const ends = transferEnds(tx)
  return {
    type: tx.type,
    amount: formatReaisInput(Math.abs(tx.amountCents)),
    date: tx.occurredOn,
    description: tx.description,
    notes: tx.notes ?? '',
    accountId: transfer ? ends.origin : tx.accountId,
    destinationAccountId: transfer ? ends.destination : '',
    categoryId: transfer ? '' : (tx.categoryId ?? ''),
    pending: tx.status === 'PENDING',
  }
}

/**
 * Trocar o tipo na criação: descarta o que não vale para o novo tipo. Para a transferência, a categoria; para receita e despesa, o destino;
 * e, ao ir de receita para despesa (ou o contrário), a categoria escolhida só fica se `fits` disser que serve ao novo tipo.
 */
export function withType(values: TransactionFormValues, type: TransactionType, fits: (categoryId: string, kind: TransactionType) => boolean): TransactionFormValues {
  const next = { ...values, type }
  if (type === 'TRANSFER') return { ...next, categoryId: '' }
  return { ...next, destinationAccountId: '', categoryId: values.categoryId !== '' && fits(values.categoryId, type) ? values.categoryId : '' }
}

export const AMOUNT_MESSAGES = {
  empty: 'Informe o valor.',
  positive: 'O valor deve ser maior que zero.',
} as const

function validateAmount(text: string): string | undefined {
  if (text.trim() === '') return AMOUNT_MESSAGES.empty
  const parsed = parseReais(text)
  if ('error' in parsed) return parsed.error
  // O sinal vem do tipo: o que se digita é sempre positivo (zero e negativo são recusados).
  if (parsed.cents <= 0) return AMOUNT_MESSAGES.positive
  return parsed.cents > MAX_CENTS ? 'O valor máximo é R$ 10.000.000.000,00.' : undefined
}

/** As mensagens de cada campo com problema (vazio = tudo certo). Na edição o tipo já vem do lançamento e não é validado. */
export function validateTransactionForm(values: TransactionFormValues, mode: 'create' | 'edit'): FieldMessages<TransactionField> {
  const messages: FieldMessages<TransactionField> = {}
  const transfer = values.type === 'TRANSFER'

  if (mode === 'create' && values.type === '') messages.type = 'Escolha o tipo.'

  const amount = validateAmount(values.amount)
  if (amount) messages.amount = amount

  const date = validateDateText(values.date)
  if (date) messages.date = date

  const description = values.description.trim()
  if (description === '') messages.description = 'Informe a descrição.'
  else if (description.length > DESCRIPTION_MAX) messages.description = `Use no máximo ${DESCRIPTION_MAX} caracteres.`

  if (values.notes.trim().length > NOTES_MAX) messages.notes = 'Use no máximo 1.000 caracteres.'

  if (values.accountId === '') messages.account = transfer ? 'Escolha a conta de origem.' : 'Escolha a conta.'

  if (transfer) {
    if (values.destinationAccountId === '') messages.destination = 'Escolha a conta de destino.'
    else if (values.destinationAccountId === values.accountId) messages.destination = 'A origem e o destino precisam ser diferentes.'
  } else if (values.type !== '' && values.categoryId === '') {
    messages.category = 'Escolha a categoria.'
  }
  return messages
}

type Shared = Pick<CreateTransaction, 'accountId' | 'amountCents' | 'occurredOn' | 'description'> & Partial<Pick<CreateTransaction, 'notes' | 'destinationAccountId' | 'categoryId'>>

/** O que os dois corpos têm em comum: valor em centavos inteiros positivos, descrição sem os espaços das pontas, observações só se houver. */
function shared(values: TransactionFormValues): Shared {
  const body: Shared = {
    accountId: values.accountId,
    amountCents: (parseReais(values.amount) as { cents: number }).cents,
    occurredOn: values.date.trim(),
    description: values.description.trim(),
  }
  if (values.notes.trim() !== '') body.notes = values.notes.trim()
  if (values.type === 'TRANSFER') body.destinationAccountId = values.destinationAccountId
  else body.categoryId = values.categoryId
  return body
}

/** O corpo do `POST`. (Chamar só com o formulário já validado.) O estado só vai quando pendente: ausente é efetivado. */
export function buildCreateTransaction(values: TransactionFormValues): CreateTransaction {
  const body: CreateTransaction = { type: values.type as TransactionType, ...shared(values) }
  if (values.pending) body.status = 'PENDING'
  return body
}

/** O corpo do `PUT`: sem o tipo (não muda) e sempre com o estado, para refletir a caixa "Pendente". Observações ausentes limpam as que havia. */
export function buildUpdateTransaction(values: TransactionFormValues): UpdateTransaction {
  return { ...shared(values), status: values.pending ? 'PENDING' : 'CLEARED' }
}
