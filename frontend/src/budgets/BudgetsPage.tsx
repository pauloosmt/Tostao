import { useMemo, useState } from 'react'
import { deleteMonthlyBudget, getMonthBudget } from '../api/budgets'
import { listCategories } from '../api/categories'
import type { BudgetItem, MonthBudget } from '../api/types'
import { useResource } from '../data/useResource'
import { currentMonth, monthLabel, shiftMonth } from '../dates/months'
import { Alert, Badge, Button, Card, MonthSwitcher, PageHeader, ProgressBar, StatTile, formatBRL } from '../design-system/src'
import { describeFailure } from '../forms/apiErrors'
import { useSubmit } from '../forms/useSubmit'
import { usePermissions } from '../permissions/usePermissions'
import { ConfirmDialog } from '../ui/ConfirmDialog'
import { ErrorNotice } from '../ui/ErrorNotice'
import { LoadingNote } from '../ui/LoadingNote'
import { buildReference } from '../transactions/reference'
import { useWorkspace } from '../workspace/useWorkspace'
import { BudgetSheet } from './BudgetSheet'
import { CopySheet } from './CopySheet'

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

type Sheet = { kind: 'create' } | { kind: 'edit'; item: BudgetItem } | { kind: 'copy' } | null

function BudgetsOf({ workspaceId, month }: { workspaceId: string; month: string }) {
  const { canWrite } = usePermissions()
  const { submitting, run } = useSubmit()
  const [sheet, setSheet] = useState<Sheet>(null)
  const [removing, setRemoving] = useState<BudgetItem | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [problem, setProblem] = useState<string | null>(null)

  // A chave leva o workspace e o mês: trocar qualquer um volta a "carregando" e descarta a resposta que ficou para trás.
  const resource = useResource(`budgets:${workspaceId}:${month}`, () => getMonthBudget(workspaceId, month))
  // As categorias de despesa ativas só interessam a quem define limites.
  const categories = useResource(`budget-categories:${workspaceId}:${canWrite}`, () => (canWrite ? listCategories(workspaceId, false) : Promise.resolve([])))
  const categoryOptions = useMemo(() => (categories.status === 'ready' ? buildReference([], categories.data).categoryOptions('EXPENSE') : []), [categories])
  const { reload } = resource

  function saved(text: string) {
    setSheet(null)
    setProblem(null)
    setNotice(text)
    reload()
  }

  async function remove(item: BudgetItem) {
    await run(async () => {
      setNotice(null)
      setProblem(null)
      try {
        await deleteMonthlyBudget(workspaceId, item.id)
        setNotice('Limite removido.')
        reload()
      } catch (error) {
        const failure = describeFailure(error)
        setProblem(failure.general ?? null)
        if (failure.kind === 'notFound') reload()
      } finally {
        setRemoving(null)
      }
    })
  }

  return (
    <>
      {notice ? <Alert tone="info">{notice}</Alert> : null}
      {problem ? <Alert tone="danger">{problem}</Alert> : null}

      {canWrite ? (
        <div className="list-toolbar">
          <Button icon="plus" disabled={categories.status !== 'ready'} onClick={() => setSheet({ kind: 'create' })}>
            Novo limite
          </Button>
          <Button variant="secondary" onClick={() => setSheet({ kind: 'copy' })}>
            Copiar para os próximos meses
          </Button>
        </div>
      ) : null}

      {resource.status === 'loading' ? <LoadingNote>Carregando o orçamento do mês…</LoadingNote> : null}
      {resource.status === 'error' ? <ErrorNotice onRetry={resource.reload}>Não foi possível carregar o orçamento. Tente de novo.</ErrorNotice> : null}
      {resource.status === 'ready' ? <MonthBudgetView data={resource.data} canWrite={canWrite} busy={submitting} onEdit={(item) => setSheet({ kind: 'edit', item })} onRemove={setRemoving} /> : null}

      {sheet?.kind === 'create' || sheet?.kind === 'edit' ? (
        <BudgetSheet
          workspaceId={workspaceId}
          month={month}
          item={sheet.kind === 'edit' ? sheet.item : undefined}
          categories={categoryOptions}
          onClose={() => setSheet(null)}
          onSaved={saved}
        />
      ) : null}
      {sheet?.kind === 'copy' ? <CopySheet workspaceId={workspaceId} month={month} onClose={() => setSheet(null)} onCopied={saved} /> : null}

      <ConfirmDialog
        open={removing !== null}
        title="Remover este limite?"
        confirmLabel="Remover"
        busy={submitting}
        onConfirm={() => {
          if (removing) void remove(removing)
        }}
        onCancel={() => setRemoving(null)}
      >
        O limite de {removing?.categoryName ?? 'todas as despesas'} sai do mês. Os gastos continuam contados no total.
      </ConfirmDialog>
    </>
  )
}

/** "R$ 410,00 de R$ 800,00". */
const spentOf = (item: BudgetItem) => `${formatBRL(item.spentCents)} de ${formatBRL(item.amountCents)}`

/** O que sobrou ou passou do limite, em palavras. */
const leftText = (item: BudgetItem) => (item.remainingCents >= 0 ? `Restam ${formatBRL(item.remainingCents)}` : `Passou ${formatBRL(-item.remainingCents)} do limite`)

interface LimitProps {
  item: BudgetItem
  label: string
  canWrite: boolean
  busy: boolean
  onEdit: (item: BudgetItem) => void
  onRemove: (item: BudgetItem) => void
}

function Limit({ item, label, canWrite, busy, onEdit, onRemove }: LimitProps) {
  return (
    <div className="stack stack--tight">
      <ProgressBar label={label} value={item.percentUsed ?? (item.spentCents > 0 ? 100 : 0)} valueLabel={spentOf(item)} />
      <div className="budget-foot">
        <p className="text-body-sm muted">{leftText(item)}</p>
        {item.categoryArchived ? <Badge tone="neutral">Arquivada</Badge> : null}
        {canWrite ? (
          <div className="item-actions">
            <Button size="sm" variant="ghost" disabled={busy} aria-label={`Editar limite de ${label}`} onClick={() => onEdit(item)}>
              Editar
            </Button>
            <Button size="sm" variant="danger-quiet" disabled={busy} aria-label={`Remover limite de ${label}`} onClick={() => onRemove(item)}>
              Remover
            </Button>
          </div>
        ) : null}
      </div>
    </div>
  )
}

interface ViewProps {
  data: MonthBudget
  canWrite: boolean
  busy: boolean
  onEdit: (item: BudgetItem) => void
  onRemove: (item: BudgetItem) => void
}

function MonthBudgetView({ data, canWrite, busy, onEdit, onRemove }: ViewProps) {
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
            <Limit item={general} label="Todas as despesas" canWrite={canWrite} busy={busy} onEdit={onEdit} onRemove={onRemove} />
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
                <Limit item={item} label={item.categoryName ?? 'Categoria'} canWrite={canWrite} busy={busy} onEdit={onEdit} onRemove={onRemove} />
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
