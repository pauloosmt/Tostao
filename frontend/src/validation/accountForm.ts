// O formulário de conta: as regras (que espelham a API; a API é a autoridade), a montagem do corpo da requisição e a ida e volta com a conta.
import { isCreditCard } from '../accounts/accountTypes'
import type { Account, AccountType, CreateAccount, UpdateAccount } from '../api/types'
import { formatReaisInput, parseReais } from '../money/reais'
import type { FieldMessages } from './account'

export type AccountField = 'name' | 'type' | 'balance' | 'limit' | 'closingDay' | 'dueDay'
export const ACCOUNT_FIELD_ORDER: readonly AccountField[] = ['name', 'type', 'balance', 'limit', 'closingDay', 'dueDay']

/** Do nome do campo na API para o campo da tela (para mostrar o erro da API no campo certo). */
export const ACCOUNT_API_FIELDS: Record<string, AccountField> = {
  name: 'name',
  type: 'type',
  initialBalanceCents: 'balance',
  creditLimitCents: 'limit',
  closingDay: 'closingDay',
  dueDay: 'dueDay',
}

export const NAME_MAX = 100

/** O que a pessoa digitou, tudo como texto (o dinheiro em reais, os dias como números escritos). */
export interface AccountFormValues {
  name: string
  /** Vazio até a pessoa escolher (na criação). */
  type: AccountType | ''
  balance: string
  limit: string
  closingDay: string
  dueDay: string
}

export const emptyAccountForm = (): AccountFormValues => ({ name: '', type: '', balance: '0,00', limit: '', closingDay: '', dueDay: '' })

/** O formulário de edição: os dados atuais da conta, com os valores no formato do campo. */
export function accountToForm(account: Account): AccountFormValues {
  return {
    name: account.name,
    type: account.type,
    balance: formatReaisInput(account.initialBalanceCents),
    limit: account.creditLimitCents === null ? '' : formatReaisInput(account.creditLimitCents),
    closingDay: account.closingDay === null ? '' : String(account.closingDay),
    dueDay: account.dueDay === null ? '' : String(account.dueDay),
  }
}

const DAY_MESSAGE = 'Use um dia de 1 a 31.'

/** Vazio é permitido (os dias do cartão são opcionais); fora isso, um inteiro de 1 a 31. */
function validateDay(text: string): string | undefined {
  const day = text.trim()
  if (day === '') return undefined
  return /^\d{1,2}$/.test(day) && Number(day) >= 1 && Number(day) <= 31 ? undefined : DAY_MESSAGE
}

function validateOptionalLimit(text: string): string | undefined {
  if (text.trim() === '') return undefined
  const parsed = parseReais(text)
  if ('error' in parsed) return parsed.error
  return parsed.cents < 0 ? 'O limite não pode ser negativo.' : undefined
}

/**
 * As mensagens de cada campo com problema (vazio = tudo certo). Os campos do cartão só valem quando o tipo é cartão: nos outros tipos o
 * que estiver neles é ignorado. Na edição o tipo já vem da conta, então não é validado.
 */
export function validateAccountForm(values: AccountFormValues, mode: 'create' | 'edit'): FieldMessages<AccountField> {
  const messages: FieldMessages<AccountField> = {}

  const name = values.name.trim()
  if (name === '') messages.name = 'Informe o nome.'
  else if (name.length > NAME_MAX) messages.name = `Use no máximo ${NAME_MAX} caracteres.`

  if (mode === 'create' && values.type === '') messages.type = 'Escolha o tipo da conta.'

  if (values.balance.trim() === '') messages.balance = 'Informe o saldo inicial.'
  else {
    const parsed = parseReais(values.balance)
    if ('error' in parsed) messages.balance = parsed.error
  }

  if (isCreditCard(values.type)) {
    const limit = validateOptionalLimit(values.limit)
    const closingDay = validateDay(values.closingDay)
    const dueDay = validateDay(values.dueDay)
    if (limit) messages.limit = limit
    if (closingDay) messages.closingDay = closingDay
    if (dueDay) messages.dueDay = dueDay
  }
  return messages
}

type CardFields = Pick<CreateAccount, 'creditLimitCents' | 'closingDay' | 'dueDay'>

/** Os campos do cartão que foram preenchidos; nenhum, se o tipo não é cartão. (Chamar só com o formulário já validado.) */
function cardFields(values: AccountFormValues): CardFields {
  if (!isCreditCard(values.type)) return {}
  const fields: CardFields = {}
  if (values.limit.trim() !== '') fields.creditLimitCents = (parseReais(values.limit) as { cents: number }).cents
  if (values.closingDay.trim() !== '') fields.closingDay = Number(values.closingDay.trim())
  if (values.dueDay.trim() !== '') fields.dueDay = Number(values.dueDay.trim())
  return fields
}

/** O corpo do `POST`: nome sem os espaços das pontas, saldo em centavos inteiros e os campos do cartão só se for cartão. */
export function buildCreateAccount(values: AccountFormValues): CreateAccount {
  return {
    name: values.name.trim(),
    type: values.type as AccountType,
    initialBalanceCents: (parseReais(values.balance) as { cents: number }).cents,
    ...cardFields(values),
  }
}

/** O corpo do `PUT`: sem o tipo (ele não muda). */
export function buildUpdateAccount(values: AccountFormValues): UpdateAccount {
  return {
    name: values.name.trim(),
    initialBalanceCents: (parseReais(values.balance) as { cents: number }).cents,
    ...cardFields(values),
  }
}
