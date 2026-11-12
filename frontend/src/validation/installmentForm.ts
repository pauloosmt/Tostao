// A compra parcelada: as regras que espelham a API (a API é a autoridade): a divisão do total em centavos exatos, as datas das parcelas, a
// validação do número de parcelas e o corpo do pedido. Tudo puro.
import type { CreateInstallmentPurchase } from '../api/types'
import { toISO } from '../dates/dates'
import { formatBRL } from '../design-system/src'
import { parseReais } from '../money/reais'
import type { TransactionFormValues } from './transactionForm'

export const MIN_INSTALLMENTS = 2
export const MAX_INSTALLMENTS = 48
/** A descrição base: com o sufixo " (48/48)" cabe nos 200 da descrição de um lançamento. */
export const INSTALLMENT_DESCRIPTION_MAX = 190
const MAX_YEAR = 2100

/** O total em `count` parcelas de centavos inteiros: cada uma vale o total dividido (para baixo) e o resto vai, um centavo por parcela, às primeiras. */
export function splitCents(totalCents: number, count: number): number[] {
  const base = Math.floor(totalCents / count)
  const rest = totalCents % count
  return Array.from({ length: count }, (_, index) => (index < rest ? base + 1 : base))
}

/** A data de cada parcela: a primeira somada a 0, 1, 2... meses, sempre a partir da primeira (o dia limitado ao último do mês, sem acumular). */
export function installmentDates(first: string, count: number): string[] {
  const [year, month, day] = first.split('-').map(Number)
  return Array.from({ length: count }, (_, index) => {
    const target = new Date(year, month - 1 + index, 1)
    const last = new Date(target.getFullYear(), target.getMonth() + 1, 0).getDate()
    return toISO(target.getFullYear(), target.getMonth() + 1, Math.min(day, last))
  })
}

/** O resumo da divisão: "3 parcelas de R$ 100,00" ou, quando sobram centavos, "3 parcelas: 1 de R$ 333,34 e 2 de R$ 333,33". */
export function describeSplit(totalCents: number, count: number): string {
  const parts = splitCents(totalCents, count)
  const big = parts[0]
  const small = parts[parts.length - 1]
  if (big === small) return `${count} parcelas de ${formatBRL(big)}`
  const bigCount = parts.filter((part) => part === big).length
  return `${count} parcelas: ${bigCount} de ${formatBRL(big)} e ${count - bigCount} de ${formatBRL(small)}`
}

/** O número de parcelas digitado: um inteiro de 2 a 48. */
export function parseInstallmentCount(text: string): number | undefined {
  const value = Number(text.trim())
  return text.trim() !== '' && Number.isInteger(value) && value >= MIN_INSTALLMENTS && value <= MAX_INSTALLMENTS ? value : undefined
}

export type InstallmentField = 'count' | 'amount' | 'description' | 'date'

/**
 * O que a compra parcelada exige além do formulário comum (o valor, a data e os demais campos já foram validados por ele): o número de
 * parcelas, um centavo por parcela, a descrição base e a última parcela dentro de 2100.
 */
export function validateInstallments(values: Pick<TransactionFormValues, 'amount' | 'date' | 'description'>, countText: string): Partial<Record<InstallmentField, string>> {
  const messages: Partial<Record<InstallmentField, string>> = {}
  const count = parseInstallmentCount(countText)
  if (count === undefined) {
    messages.count = `Informe de ${MIN_INSTALLMENTS} a ${MAX_INSTALLMENTS} parcelas.`
    return messages
  }
  const total = parseReais(values.amount)
  if ('cents' in total && total.cents < count) messages.amount = 'O valor total deve ser de pelo menos 1 centavo por parcela.'
  if (values.description.trim().length > INSTALLMENT_DESCRIPTION_MAX) messages.description = `Use no máximo ${INSTALLMENT_DESCRIPTION_MAX} caracteres.`
  const last = installmentDates(values.date.trim(), count)[count - 1]
  if (Number(last.slice(0, 4)) > MAX_YEAR) messages.date = 'A última parcela passaria de 2100.'
  return messages
}

/** O corpo do `POST` da compra parcelada. (Chamar só com o formulário e as parcelas já validados.) A primeira parcela só vai pendente quando marcada. */
export function buildInstallmentPurchase(values: TransactionFormValues, count: number): CreateInstallmentPurchase {
  const body: CreateInstallmentPurchase = {
    accountId: values.accountId,
    categoryId: values.categoryId,
    totalCents: (parseReais(values.amount) as { cents: number }).cents,
    installmentCount: count,
    firstOccurredOn: values.date.trim(),
    description: values.description.trim(),
  }
  if (values.notes.trim() !== '') body.notes = values.notes.trim()
  if (values.pending) body.firstInstallmentStatus = 'PENDING'
  return body
}
