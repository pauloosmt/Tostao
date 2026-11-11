// Os tipos de conta: o que a API manda (CHECKING...), o que o design system desenha (corrente...) e o texto do seletor.
import type { Account, AccountType } from '../api/types'
import { formatBRL, type AccountType as CardType } from '../design-system/src'

export const ACCOUNT_TYPE_OPTIONS: readonly { value: AccountType; label: string }[] = [
  { value: 'CHECKING', label: 'Conta corrente' },
  { value: 'SAVINGS', label: 'Poupança' },
  { value: 'CASH', label: 'Dinheiro' },
  { value: 'CREDIT_CARD', label: 'Cartão de crédito' },
  { value: 'INVESTMENT', label: 'Investimento' },
]

const TO_CARD: Record<AccountType, CardType> = {
  CHECKING: 'corrente',
  SAVINGS: 'poupanca',
  CASH: 'dinheiro',
  CREDIT_CARD: 'cartao',
  INVESTMENT: 'investimento',
}

/** O tipo no `AccountCard`. Um tipo que o app não conhece (outra versão da API) vai como está e o cartão o mostra como "Conta". */
export function toCardType(type: string): CardType {
  return (TO_CARD as Record<string, CardType>)[type] ?? (type as CardType)
}

export const isCreditCard = (type: string): boolean => type === 'CREDIT_CARD'

/**
 * A linha de apoio de um cartão ("Fecha dia 10 · vence dia 17 · limite R$ 5.000,00"): só o que foi informado, nunca um valor inventado.
 * Conta que não é cartão não tem linha.
 */
export function cardNote(account: Pick<Account, 'type' | 'closingDay' | 'dueDay' | 'creditLimitCents'>): string | undefined {
  if (!isCreditCard(account.type)) return undefined
  const parts: string[] = []
  if (account.closingDay !== null) parts.push(`Fecha dia ${account.closingDay}`)
  if (account.dueDay !== null) parts.push(`vence dia ${account.dueDay}`)
  if (account.creditLimitCents !== null) parts.push(`limite ${formatBRL(account.creditLimitCents)}`)
  return parts.length > 0 ? parts.join(' · ') : undefined
}
