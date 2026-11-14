import { useMemo, useState } from 'react'
import { deletePeriodBudget, getPeriodBudget, type BudgetPeriodKind } from '../api/budgets'
import { listCategories } from '../api/categories'
import type { BudgetItem, PeriodBudget } from '../api/types'
import { useResource } from '../data/useResource'
import { currentMonth, monthLabel, shiftMonth } from '../dates/months'
import { currentWeek, shiftWeek, weekLabel } from '../dates/weeks'
import { Alert, Badge, Button, Card, IconButton, MonthSwitcher, PageHeader, ProgressBar, Segmented, StatTile, formatBRL } from '../design-system/src'
import { describeFailure } from '../forms/apiErrors'
import { useSubmit } from '../forms/useSubmit'
import { usePermissions } from '../permissions/usePermissions'
import { ConfirmDialog } from '../ui/ConfirmDialog'
import { ErrorNotice } from '../ui/ErrorNotice'
import { LoadingNote } from '../ui/LoadingNote'
import { buildReference } from '../transactions/reference'
import { useMediaQuery } from '../layout/useMediaQuery'
import { ButtonLink } from '../ui/ButtonLink'
import { useWorkspace } from '../workspace/useWorkspace'
import { BudgetSheet } from './BudgetSheet'
import { CopySheet } from './CopySheet'

const KINDS = [
  { value: 'month', label: 'Mensal' },
  { value: 'week', label: 'Semanal' },
]

/** As palavras de cada período, para a tela dizer "mês" ou "semana" sem repetir o código. */
const WORDS = {
  month: { context: 'Limites de gasto do mês', loading: 'Carregando o orçamento do mês…', spent: 'Gasto no mês', empty: 'Nenhum limite neste mês', noneHint: 'Nenhum limite definido para este mês.', noGeneral: 'Sem limite geral neste mês.', total: 'Total gasto no mês', copy: 'Copiar para os próximos meses' },
  week: { context: 'Limites de gasto da semana', loading: 'Carregando o orçamento da semana…', spent: 'Gasto na semana', empty: 'Nenhum limite nesta semana', noneHint: 'Nenhum limite definido para esta semana.', noGeneral: 'Sem limite geral nesta semana.', total: 'Total gasto na semana', copy: 'Copiar para as próximas semanas' },
} as const

export function BudgetsPage() {
  const { current } = useWorkspace()
  // Na barra de baixo (celular) não cabem Contas, Recorrências e Categorias: os atalhos ficam aqui. No menu lateral eles já existem.
  const wide = useMediaQuery('(min-width: 960px)')
  // O período escolhido (mensal ou semanal) e o mês e a semana de cada um ficam só na memória da tela.
  const [kind, setKind] = useState<BudgetPeriodKind>('month')
  const [month, setMonth] = useState(() => currentMonth())
  const [week, setWeek] = useState(() => currentWeek())
  const period = kind === 'month' ? month : week
  const label = kind === 'month' ? monthLabel(month) : weekLabel(week)
  const go = (delta: number) => (kind === 'month' ? setMonth((now) => shiftMonth(now, delta) ?? now) : setWeek((now) => shiftWeek(now, delta) ?? now))

  return (
    <>
      <PageHeader
        title="Orçamentos"
        context={WORDS[kind].context}
        action={
          kind === 'month' ? (
            <MonthSwitcher label={label} onPrev={() => go(-1)} onNext={() => go(1)} />
          ) : (
            <div className="tx-month">
              <IconButton icon="chevronLeft" label="Semana anterior" onClick={() => go(-1)} />
              <span className="tx-month__label text-label">{label}</span>
              <IconButton icon="chevronRight" label="Próxima semana" onClick={() => go(1)} />
            </div>
          )
        }
      />
      {wide ? null : (
        <div className="profile-actions">
          <ButtonLink variant="secondary" to="/contas">Contas</ButtonLink>
          <ButtonLink variant="secondary" to="/recorrencias">Recorrências</ButtonLink>
          <ButtonLink variant="secondary" to="/categorias">Categorias</ButtonLink>
        </div>
      )}
      <Segmented label="Período" value={kind} options={KINDS} onChange={(value) => setKind(value as BudgetPeriodKind)} />
      {current ? <BudgetsOf workspaceId={current.id} kind={kind} period={period} periodLabel={label} /> : <Card><p className="text-body">Você não participa de nenhum workspace.</p></Card>}
    </>
  )
}

type Sheet = { kind: 'create' } | { kind: 'edit'; item: BudgetItem } | { kind: 'copy' } | null

interface BudgetsOfProps {
  workspaceId: string
  kind: BudgetPeriodKind
  period: string
  periodLabel: string
}

function BudgetsOf({ workspaceId, kind, period, periodLabel }: BudgetsOfProps) {
  const { canWrite } = usePermissions()
  const { submitting, run } = useSubmit()
  const [sheet, setSheet] = useState<Sheet>(null)
  const [removing, setRemoving] = useState<BudgetItem | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const words = WORDS[kind]

  // A chave leva o workspace, o tipo e o período: trocar qualquer um volta a "carregando" e descarta a resposta que ficou para trás.
  const resource = useResource(`budgets:${workspaceId}:${kind}:${period}`, () => getPeriodBudget(workspaceId, kind, period))
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
        await deletePeriodBudget(workspaceId, kind, item.id)
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
            {words.copy}
          </Button>
        </div>
      ) : null}

      {resource.status === 'loading' ? <LoadingNote>{words.loading}</LoadingNote> : null}
      {resource.status === 'error' ? <ErrorNotice onRetry={resource.reload}>Não foi possível carregar o orçamento. Tente de novo.</ErrorNotice> : null}
      {resource.status === 'ready' ? (
        <PeriodBudgetView data={resource.data} kind={kind} canWrite={canWrite} busy={submitting} onEdit={(item) => setSheet({ kind: 'edit', item })} onRemove={setRemoving} />
      ) : null}

      {sheet?.kind === 'create' || sheet?.kind === 'edit' ? (
        <BudgetSheet
          workspaceId={workspaceId}
          kind={kind}
          period={period}
          periodLabel={periodLabel}
          item={sheet.kind === 'edit' ? sheet.item : undefined}
          categories={categoryOptions}
          onClose={() => setSheet(null)}
          onSaved={saved}
        />
      ) : null}
      {sheet?.kind === 'copy' ? <CopySheet workspaceId={workspaceId} kind={kind} period={period} periodLabel={periodLabel} onClose={() => setSheet(null)} onCopied={saved} /> : null}

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
        O limite de {removing?.categoryName ?? 'todas as despesas'} sai {kind === 'month' ? 'do mês' : 'da semana'}. Os gastos continuam contados no total.
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
  data: PeriodBudget
  kind: BudgetPeriodKind
  canWrite: boolean
  busy: boolean
  onEdit: (item: BudgetItem) => void
  onRemove: (item: BudgetItem) => void
}

function PeriodBudgetView({ data, kind, canWrite, busy, onEdit, onRemove }: ViewProps) {
  const words = WORDS[kind]
  const general = data.budgets.find((item) => item.categoryId === null)
  const byCategory = data.budgets.filter((item) => item.categoryId !== null)

  if (data.budgets.length === 0) {
    return (
      <>
        <StatTile label={words.spent} cents={data.totalSpentCents} kind="expense" hint={words.noneHint} />
        <Card>
          <div className="stack stack--tight">
            <p className="title">{words.empty}</p>
            <p className="text-body muted">Um limite geral ou por categoria mostra quanto já foi gasto.</p>
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
          <StatTile label={words.spent} cents={data.totalSpentCents} kind="expense" hint={words.noGeneral} />
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
        {words.total}: {formatBRL(data.totalSpentCents)}.
        {byCategory.length > 0 ? ` Fora dos limites de categoria: ${formatBRL(data.spentOutsideCategoryLimitsCents)}.` : ''}
      </p>
    </>
  )
}
