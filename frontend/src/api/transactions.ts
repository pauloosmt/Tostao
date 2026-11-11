// As chamadas de lançamentos (specs 004 e 009), no workspace atual (`wid`). Valores em centavos inteiros; datas `AAAA-MM-DD`.
import { request } from './client'
import type { CreateTransaction, Transaction, TransactionPage, TransactionStatus, TransactionType, UpdateTransaction } from './types'

/** O tamanho da página (o padrão da API). */
export const PAGE_SIZE = 20

/** Os filtros da lista: cada um é opcional e eles se combinam com "e". Texto vazio vale como "sem filtro". */
export interface TransactionFilters {
  from?: string
  to?: string
  accountId?: string
  categoryId?: string
  type?: TransactionType
  status?: TransactionStatus
  q?: string
}

const base = (wid: string) => `/workspaces/${encodeURIComponent(wid)}/transactions`
const one = (wid: string, id: string) => `${base(wid)}/${encodeURIComponent(id)}`

/** A consulta: página, tamanho e só os filtros preenchidos, na ordem do contrato. O `URLSearchParams` codifica o texto (`%`, espaço...). */
export function listQuery(page: number, filters: TransactionFilters): string {
  const params = new URLSearchParams()
  params.set('page', String(page))
  params.set('size', String(PAGE_SIZE))
  for (const key of ['from', 'to', 'accountId', 'categoryId', 'type', 'status', 'q'] as const) {
    const value = filters[key]
    if (value !== undefined && value !== '') params.set(key, value)
  }
  return params.toString()
}

export const listTransactions = (wid: string, page: number, filters: TransactionFilters = {}) =>
  request<TransactionPage>('GET', `${base(wid)}?${listQuery(page, filters)}`)

export const getTransaction = (wid: string, id: string) => request<Transaction>('GET', one(wid, id))

export const createTransaction = (wid: string, body: CreateTransaction) => request<Transaction>('POST', base(wid), { body })

/** Numa transferência, as duas pontas mudam juntas. */
export const updateTransaction = (wid: string, id: string, body: UpdateTransaction) => request<Transaction>('PUT', one(wid, id), { body })

/** Efetiva um pendente (numa transferência, as duas pontas); um já efetivado responde 200 sem efeito. */
export const clearTransaction = (wid: string, id: string) => request<Transaction>('POST', `${one(wid, id)}/clear`)

/** Exclui (204); numa transferência, as duas pontas. */
export const deleteTransaction = (wid: string, id: string) => request<void>('DELETE', one(wid, id))
