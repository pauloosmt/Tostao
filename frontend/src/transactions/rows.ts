// Do lançamento da API para a linha que a lista desenha: valor absoluto e tipo (o `Money` põe o sinal), nomes no lugar de identificadores,
// selos e o agrupamento por dia. Tudo puro.
import type { Transaction } from '../api/types'

/** Como a lista descobre o nome de uma conta e de uma categoria (inclusive as arquivadas). */
export interface Names {
  accountName: (id: string) => string
  categoryName: (id: string) => string | null
}

export interface DisplayRow {
  id: string
  description: string
  /** O valor absoluto, em centavos: o `TransactionRow` põe o sinal pelo tipo. */
  cents: number
  kind: 'income' | 'expense' | 'transfer'
  account: string
  /** A categoria; na transferência, a direção ("Para Reserva" / "De Carteira"). */
  category: string
  /** `AAAA-MM-DD`. */
  date: string
  pending: boolean
  tags: string[]
}

export interface DayGroup {
  /** `AAAA-MM-DD`. */
  day: string
  rows: DisplayRow[]
}

const KIND: Record<string, DisplayRow['kind']> = { INCOME: 'income', EXPENSE: 'expense', TRANSFER: 'transfer' }

/** Os selos de um lançamento que não nasceu do cadastro simples: parcela, recorrência, importação. */
export function tagsOf(tx: Pick<Transaction, 'installmentNumber' | 'installmentCount' | 'recurrenceId' | 'imported'>): string[] {
  const tags: string[] = []
  if (tx.installmentNumber !== null && tx.installmentCount !== null) tags.push(`Parcela ${tx.installmentNumber} de ${tx.installmentCount}`)
  if (tx.recurrenceId !== null) tags.push('Recorrente')
  if (tx.imported) tags.push('Importado')
  return tags
}

export function toDisplayRow(tx: Transaction, names: Names): DisplayRow {
  const known = tx.type in KIND
  let category: string
  if (!known) category = 'Lançamento' // um tipo que o app não conhece (outra versão da API): aparece sem quebrar
  else if (tx.type === 'TRANSFER') {
    const other = tx.counterpartAccountId === null ? '' : names.accountName(tx.counterpartAccountId)
    category = tx.amountCents < 0 ? `Para ${other}` : `De ${other}`
  } else {
    category = (tx.categoryId === null ? null : names.categoryName(tx.categoryId)) ?? 'Sem categoria'
  }
  return {
    id: tx.id,
    description: tx.description,
    cents: Math.abs(tx.amountCents),
    kind: KIND[tx.type] ?? 'expense',
    account: names.accountName(tx.accountId),
    category,
    date: tx.occurredOn,
    pending: tx.status === 'PENDING',
    tags: tagsOf(tx),
  }
}

/** Agrupa as linhas consecutivas do mesmo dia (a API já entrega em ordem; a ordem das linhas é preservada). */
export function groupByDay(rows: DisplayRow[]): DayGroup[] {
  const groups: DayGroup[] = []
  for (const row of rows) {
    const last = groups[groups.length - 1]
    if (last !== undefined && last.day === row.date) last.rows.push(row)
    else groups.push({ day: row.date, rows: [row] })
  }
  return groups
}
