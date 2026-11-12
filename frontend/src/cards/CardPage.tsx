import { useState } from 'react'
import { useParams } from 'react-router'
import { ApiError } from '../api/errors'
import { getCardSummary, getInvoice } from '../api/invoices'
import type { CardSummary, Invoice, InvoiceLine, InvoiceStatus } from '../api/types'
import { useResource } from '../data/useResource'
import { formatDate } from '../dates/dates'
import { monthLabel, shiftMonth } from '../dates/months'
import { Badge, Card, MonthSwitcher, PageHeader, ProgressBar, StatTile, TransactionRow, formatBRL } from '../design-system/src'
import { describeFailure } from '../forms/apiErrors'
import { usePermissions } from '../permissions/usePermissions'
import { ButtonLink } from '../ui/ButtonLink'
import { ErrorNotice } from '../ui/ErrorNotice'
import { LoadingNote } from '../ui/LoadingNote'
import { MissingItem } from '../ui/MissingItem'
import { useWorkspace } from '../workspace/useWorkspace'

const STATUS: Record<InvoiceStatus, { label: string; tone: 'neutral' | 'brand' | 'warning' | 'danger' }> = {
  OPEN: { label: 'Aberta', tone: 'brand' },
  CLOSED: { label: 'Fechada', tone: 'neutral' },
  PAID: { label: 'Paga', tone: 'neutral' },
  OVERDUE: { label: 'Vencida', tone: 'danger' },
}

/** `/contas/:id/fatura`: o resumo do cartão (limite, devido, disponível) e as faturas por mês de vencimento. Só leitura. */
export function CardPage() {
  const { current } = useWorkspace()
  const { id = '' } = useParams()
  return current ? <CardOf workspaceId={current.id} accountId={id} /> : null
}

function CardOf({ workspaceId, accountId }: { workspaceId: string; accountId: string }) {
  const { canWrite } = usePermissions()
  const summary = useResource(`card:${workspaceId}:${accountId}`, () => getCardSummary(workspaceId, accountId))

  if (summary.status === 'loading') return <LoadingNote>Carregando o cartão…</LoadingNote>
  if (summary.status === 'error') {
    if (summary.error instanceof ApiError && summary.error.status === 404) return <MissingItem title="Cartão não encontrado" backTo="/contas" backLabel="Voltar às contas" />
    // A conta não é um cartão (409): a API diz por quê.
    if (summary.error instanceof ApiError && summary.error.status === 409) {
      return (
        <>
          <PageHeader title="Fatura" />
          <Card>
            <div className="stack">
              <p className="text-body">{describeFailure(summary.error).general}</p>
              <div><ButtonLink to="/contas">Voltar às contas</ButtonLink></div>
            </div>
          </Card>
        </>
      )
    }
    return <ErrorNotice onRetry={summary.reload}>Não foi possível carregar o cartão. Tente de novo.</ErrorNotice>
  }

  const card = summary.data
  return (
    <>
      <PageHeader title={card.name} context={card.archived ? 'Cartão de crédito · Arquivado' : 'Cartão de crédito'} />
      <CardSummaryView card={card} />
      {card.openInvoice === null ? (
        <Card>
          <div className="stack stack--tight">
            <p className="title">Faturas indisponíveis</p>
            <p className="text-body muted">Para ver as faturas, o cartão precisa dos dias de fechamento e de vencimento.</p>
            {canWrite && !card.archived ? <div><ButtonLink variant="primary" to={`/contas/${accountId}`}>Editar a conta</ButtonLink></div> : null}
          </div>
        </Card>
      ) : (
        <InvoiceView workspaceId={workspaceId} accountId={accountId} openMonth={card.openInvoice.month} />
      )}
    </>
  )
}

function CardSummaryView({ card }: { card: CardSummary }) {
  if (card.limitCents === null || card.availableCents === null) {
    return <StatTile label="Devido" cents={card.owedCents} kind="expense" hint="Sem limite cadastrado." />
  }
  const over = card.availableCents < 0
  return (
    <section className="stack" aria-label="Limite do cartão">
      <div className="home-totals">
        <StatTile label="Limite" cents={card.limitCents} kind="balance" />
        <StatTile label="Devido" cents={card.owedCents} kind="expense" />
        <StatTile label="Disponível" cents={card.availableCents} kind="balance" hint={over ? `Passou ${formatBRL(-card.availableCents)} do limite` : undefined} />
      </div>
      <ProgressBar label="Uso do limite" value={card.limitCents === 0 ? (card.owedCents > 0 ? 100 : 0) : (card.owedCents / card.limitCents) * 100} valueLabel={`${formatBRL(card.owedCents)} de ${formatBRL(card.limitCents)}`} />
    </section>
  )
}

function InvoiceView({ workspaceId, accountId, openMonth }: { workspaceId: string; accountId: string; openMonth: string }) {
  const [month, setMonth] = useState(openMonth)
  const go = (delta: number) => setMonth((now) => shiftMonth(now, delta) ?? now)
  // A chave leva o cartão e o mês: trocar volta a "carregando" e descarta a resposta que ficou para trás.
  const invoice = useResource(`invoice:${workspaceId}:${accountId}:${month}`, () => getInvoice(workspaceId, accountId, month))

  return (
    <section className="stack" aria-labelledby="invoice-title">
      <div className="list-toolbar">
        <h2 id="invoice-title" className="heading-2">Fatura</h2>
        <MonthSwitcher label={monthLabel(month)} onPrev={() => go(-1)} onNext={() => go(1)} />
      </div>
      {invoice.status === 'loading' ? <LoadingNote>Carregando a fatura…</LoadingNote> : null}
      {invoice.status === 'error' ? <ErrorNotice onRetry={invoice.reload}>Não foi possível carregar a fatura. Tente de novo.</ErrorNotice> : null}
      {invoice.status === 'ready' ? <InvoiceBody invoice={invoice.data} /> : null}
    </section>
  )
}

const lineKind = (line: InvoiceLine) => (line.chargeCents < 0 ? 'income' : 'expense')

function InvoiceBody({ invoice }: { invoice: Invoice }) {
  const status = STATUS[invoice.status] ?? { label: invoice.status, tone: 'neutral' as const }
  return (
    <>
      <Card>
        <div className="stack stack--tight">
          <div className="budget-foot">
            <p className="text-label muted">Total da fatura</p>
            <Badge tone={status.tone}>{status.label}</Badge>
          </div>
          <StatTile label="Total" cents={invoice.totalCents} kind="expense" size="lg" className="invoice-total" />
          <p className="text-body-sm muted">
            Pago {formatBRL(invoice.paidCents)} · Restante {formatBRL(invoice.remainingCents)}
          </p>
          <p className="text-body-sm muted">
            Período de {formatDate(invoice.periodStart)} a {formatDate(invoice.closingDate)} · fecha em {formatDate(invoice.closingDate)} · vence em {formatDate(invoice.dueDate)}
          </p>
        </div>
      </Card>
      {invoice.lines.length === 0 ? (
        <p className="text-body muted">Nenhum lançamento nesta fatura.</p>
      ) : (
        <ul className="transaction-list" aria-label="Lançamentos da fatura">
          {invoice.lines.map((line) => (
            <li key={line.id}>
              <TransactionRow
                description={line.description}
                cents={Math.abs(line.chargeCents)}
                kind={lineKind(line)}
                category={line.categoryName ?? (line.type === 'TRANSFER' ? 'Transferência' : 'Sem categoria')}
                date={formatDate(line.date)}
              />
            </li>
          ))}
        </ul>
      )}
    </>
  )
}
