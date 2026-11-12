// Os limites de gasto por mês (spec 007). Valores em centavos inteiros; `categoryId` nulo é o limite geral.
import { request } from './client'
import type { CopyBudgetsResult, MonthBudget, MonthlyBudget } from './types'

const base = (wid: string) => `/workspaces/${encodeURIComponent(wid)}/monthly-budgets`

/** O acompanhamento de um mês (`AAAA-MM`): cada limite com o gasto, o restante e o estado. */
export const getMonthBudget = (wid: string, month: string) => request<MonthBudget>('GET', `${base(wid)}?month=${encodeURIComponent(month)}`)

/** Define o limite de um escopo e mês: cria, ou troca o valor se já existe. */
export const defineMonthlyBudget = (wid: string, body: { month: string; categoryId?: string; amountCents: number }) =>
  request<MonthlyBudget>('PUT', base(wid), { body })

export const updateMonthlyBudget = (wid: string, id: string, amountCents: number) =>
  request<MonthlyBudget>('PUT', `${base(wid)}/${encodeURIComponent(id)}`, { body: { amountCents } })

export const deleteMonthlyBudget = (wid: string, id: string) => request('DELETE', `${base(wid)}/${encodeURIComponent(id)}`)

/** Copia os limites de `fromMonth` para os próximos `months` meses (1 a 24), só onde ainda não há limite. */
export const copyMonthlyBudgets = (wid: string, body: { fromMonth: string; months: number }) => request<CopyBudgetsResult>('POST', `${base(wid)}/copy`, { body })
