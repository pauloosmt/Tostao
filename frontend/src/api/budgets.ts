// Os limites de gasto por mês (spec 007) e por semana (spec 006). Os dois têm o mesmo formato; só mudam a rota, o texto do período
// (`AAAA-MM` ou `AAAA-Www`) e o nome dos campos. Valores em centavos inteiros; `categoryId` nulo é o limite geral.
import { request } from './client'
import type { CopyBudgetsResult, MonthBudget, MonthlyBudget, PeriodBudget, WeekBudget } from './types'

export type BudgetPeriodKind = 'month' | 'week'

const base = (wid: string, kind: BudgetPeriodKind) => `/workspaces/${encodeURIComponent(wid)}/${kind === 'month' ? 'monthly-budgets' : 'budgets'}`

/** O acompanhamento de um período: cada limite com o gasto, o restante e o estado (sempre no formato `period`). */
export async function getPeriodBudget(wid: string, kind: BudgetPeriodKind, period: string): Promise<PeriodBudget> {
  if (kind === 'month') {
    const raw = await request<MonthBudget>('GET', `${base(wid, kind)}?month=${encodeURIComponent(period)}`)
    return { period: raw.month, totalSpentCents: raw.totalSpentCents, spentOutsideCategoryLimitsCents: raw.spentOutsideCategoryLimitsCents, budgets: raw.budgets }
  }
  const raw = await request<WeekBudget>('GET', `${base(wid, kind)}?week=${encodeURIComponent(period)}`)
  return { period: raw.week, totalSpentCents: raw.totalSpentCents, spentOutsideCategoryLimitsCents: raw.spentOutsideCategoryLimitsCents, budgets: raw.budgets }
}

/** Define o limite de um escopo e período: cria, ou troca o valor se já existe. */
export const definePeriodBudget = (wid: string, kind: BudgetPeriodKind, body: { period: string; categoryId?: string; amountCents: number }) =>
  request<MonthlyBudget>('PUT', base(wid, kind), { body: { [kind]: body.period, ...(body.categoryId ? { categoryId: body.categoryId } : {}), amountCents: body.amountCents } })

export const updatePeriodBudget = (wid: string, kind: BudgetPeriodKind, id: string, amountCents: number) =>
  request<MonthlyBudget>('PUT', `${base(wid, kind)}/${encodeURIComponent(id)}`, { body: { amountCents } })

export const deletePeriodBudget = (wid: string, kind: BudgetPeriodKind, id: string) => request('DELETE', `${base(wid, kind)}/${encodeURIComponent(id)}`)

/** Copia os limites de `from` para os próximos `count` períodos (1 a 24 meses, 1 a 52 semanas), só onde ainda não há limite. */
export const copyPeriodBudgets = (wid: string, kind: BudgetPeriodKind, body: { from: string; count: number }) =>
  request<CopyBudgetsResult>('POST', `${base(wid, kind)}/copy`, { body: kind === 'month' ? { fromMonth: body.from, months: body.count } : { fromWeek: body.from, weeks: body.count } })
