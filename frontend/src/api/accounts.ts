// As chamadas de contas (spec 002). Todas valem para um workspace: o `wid` é o workspace atual. Valores em centavos inteiros.
import { request } from './client'
import type { Account, BalanceReport, CreateAccount, UpdateAccount } from './types'

const base = (wid: string) => `/workspaces/${encodeURIComponent(wid)}`
const one = (wid: string, id: string) => `${base(wid)}/accounts/${encodeURIComponent(id)}`

/** As contas ordenadas por nome, com o saldo calculado; as arquivadas só com `includeArchived`. */
export const listAccounts = (wid: string, includeArchived = false) =>
  request<Account[]>('GET', `${base(wid)}/accounts${includeArchived ? '?includeArchived=true' : ''}`)

export const getAccount = (wid: string, id: string) => request<Account>('GET', one(wid, id))

export const createAccount = (wid: string, body: CreateAccount) => request<Account>('POST', `${base(wid)}/accounts`, { body })

export const updateAccount = (wid: string, id: string, body: UpdateAccount) => request<Account>('PUT', one(wid, id), { body })

export const archiveAccount = (wid: string, id: string) => request<Account>('POST', `${one(wid, id)}/archive`)

export const restoreAccount = (wid: string, id: string) => request<Account>('POST', `${one(wid, id)}/restore`)

/** O saldo de cada conta ativa e o total, consistentes entre si. */
export const balanceReport = (wid: string) => request<BalanceReport>('GET', `${base(wid)}/reports/balance`)
