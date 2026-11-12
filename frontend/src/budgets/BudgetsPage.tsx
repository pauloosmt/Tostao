import { useState } from 'react'
import { getMonthBudget } from '../api/budgets'
import type { BudgetItem, MonthBudget } from '../api/types'
import { useResource } from '../data/useResource'
import { currentMonth, monthLabel, shiftMonth } from '../dates/months'
import { Badge, Card, MonthSwitcher, PageHeader, ProgressBar, StatTile, formatBRL } from '../design-system/src'
import { ErrorNotice } from '../ui/ErrorNotice'
import { LoadingNote } from '../ui/LoadingNote'
import { useWorkspace } from '../workspace/useWorkspace'

export function BudgetsPage() {
  const { current } = useWorkspace()
  const [month, setMonth] = useState(() => currentMonth())
  const go = (delta: number) => setMonth((now) => shiftMonth(now, delta) ?? now)

  return (
    <>
      <PageHeader title="Orçamentos" context="Limites de gasto do mês" action={<MonthSwitcher label={monthLabel(month)} onPrev={() => go(-1)} onNext={() => go(1)} />} />
      {current ? <BudgetsOf workspaceId={current.id} month={month} /> : <Card><p className="text-body">Você não participa de nenhum workspace.</p></Card>}
    </>
  )
}

function BudgetsOf({ workspaceId, month }: { workspaceId: string; month: string }) {
  // A chave leva o workspace e o mês: trocar qualquer um volta a "carregando" e descarta a resposta que ficou para trás.
  const resource = useResource(`budgets:${workspaceId}:${month}`, () => getMonthBudget(workspaceId, month))

  if (resource.status === 'loading') return <LoadingNote>Carregando o orçamento do mês…</LoadingNote>
  if (resource.status === 'error') return <ErrorNotice onRetry={resource.reload}>Não foi possível carregar o orçamento. Tente de novo.</ErrorNotice>
  return <MonthBudgetView data={resource.data} />
}

/** "R$ 410,00 de R$ 800,00". */
const spentOf = (item: BudgetItem) => `${formatBRL(item.spentCents)} de ${formatBRL(item.amountCents)}`

/** O que sobrou ou passou do limite, em palavras. */
const leftText = (item: BudgetItem) => (item.remainingCents >= 0 ? `Restam ${formatBRL(item.remainingCents)}` : `Passou ${formatBRL(-item.remainingCents)} do limite`)

function Limit({ item, label }: { item: BudgetItem; label: string }) {
  return (
    <div className="stack stack--tight">
      <ProgressBar label={label} value={item.percentUsed ?? (item.spentCents > 0 ? 100 : 0)} valueLabel={spentOf(item)} />
      <p className="text-body-sm muted">{leftText(item)}</p>
    </div>
  )
}

function MonthBudgetView({ data }: { data: MonthBudget }) {
  const general = data.budgets.find((item) => item.categoryId === null)
  const byCategory = data.budgets.filter((item) => item.categoryId !== null)

  if (data.budgets.length === 0) {
    return (
      <>
        <StatTile label="Gasto no mês" cents={data.totalSpentCents} kind="expense" hint="Nenhum limite definido para este mês." />
        <Card>
          <div className="stack stack--tight">
            <p className="title">Nenhum limite neste mês</p>
            <p className="text-body muted">Um limite geral ou por categoria mostra quanto do mês já foi gasto.</p>
          </div>
        </Card>
      </>
    )
  }

  return (
    <>
      <section className="stack" aria-label="Limite geral">
        {general ? (
          <Card title="Limite geral">
            <Limit item={general} label="Todas as despesas" />
          </Card>
        ) : (
          <StatTile label="Gasto no mês" cents={data.totalSpentCents} kind="expense" hint="Sem limite geral neste mês." />
        )}
      </section>

      {byCategory.length > 0 ? (
        <section className="stack" aria-labelledby="budget-categories">
          <h2 id="budget-categories" className="heading-2">Por categoria</h2>
          <ul className="home-categories">
            {byCategory.map((item) => (
              <li key={item.id}>
                <Limit item={item} label={item.categoryName ?? 'Categoria'} />
                {item.categoryArchived ? <Badge tone="neutral">Arquivada</Badge> : null}
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <p className="text-body-sm muted">
        Total gasto no mês: {formatBRL(data.totalSpentCents)}.
        {byCategory.length > 0 ? ` Fora dos limites de categoria: ${formatBRL(data.spentOutsideCategoryLimitsCents)}.` : ''}
      </p>
    </>
  )
}
