// O cartão de crédito (spec 008). Só leitura. Valores em centavos inteiros; as faturas são calculadas, nunca guardadas.
import { request } from './client'
import type { CardSummary, Invoice } from './types'

const card = (wid: string, accountId: string) => `/workspaces/${encodeURIComponent(wid)}/accounts/${encodeURIComponent(accountId)}`

/** Limite, devido, disponível e a fatura aberta do cartão. */
export const getCardSummary = (wid: string, accountId: string) => request<CardSummary>('GET', `${card(wid, accountId)}/card-summary`)

/** A fatura do mês de vencimento `AAAA-MM`, com os lançamentos que a compõem. */
export const getInvoice = (wid: string, accountId: string, month: string) => request<Invoice>('GET', `${card(wid, accountId)}/invoices/${encodeURIComponent(month)}`)
