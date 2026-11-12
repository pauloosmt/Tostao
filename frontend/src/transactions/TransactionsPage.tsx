import { useEffect, useRef, useState } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router'
import { listTransactions } from '../api/transactions'
import { Alert, Badge, Button, Card, PageHeader, TransactionRow } from '../design-system/src'
import { useResource } from '../data/useResource'
import { formatDay } from '../dates/dates'
import { usePermissions } from '../permissions/usePermissions'
import { ButtonLink } from '../ui/ButtonLink'
import { ErrorNotice } from '../ui/ErrorNotice'
import { LoadingNote } from '../ui/LoadingNote'
import { useWorkspace } from '../workspace/useWorkspace'
import { summarize } from './filterSummary'
import { Pager } from './Pager'
import { TransactionFilters } from './TransactionFilters'
import { useReferenceData } from './reference'
import { groupByDay, toDisplayRow } from './rows'
import type { SheetContext } from './sheetContext'
import { useTransactionFilters } from './useFilters'

export function TransactionsPage() {
  const { current } = useWorkspace()

  return current ? (
    <TransactionsOf workspaceId={current.id} />
  ) : (
    <>
      <PageHeader title="Lançamentos" />
      <Card><p className="text-body">Você não participa de nenhum workspace.</p></Card>
    </>
  )
}

function TransactionsOf({ workspaceId }: { workspaceId: string }) {
  const { canWrite } = usePermissions()
  const navigate = useNavigate()
  const location = useLocation()
  const { filters, page, activeCount, clear, setPage } = useTransactionFilters()
  const [filtersOpen, setFiltersOpen] = useState(false)

  // O aviso do que acabou de acontecer (o lançamento foi registrado) vem no estado do roteador: é lido e tirado do histórico. A lista
  // continua montada atrás da folha, então o aviso pode chegar com ela já aberta.
  const incoming = (location.state as { notice?: string } | null)?.notice ?? null
  const [notice, setNotice] = useState<string | null>(incoming)
  const [seen, setSeen] = useState(incoming)
  if (incoming !== seen) {
    setSeen(incoming)
    if (incoming !== null) setNotice(incoming)
  }
  useEffect(() => {
    if (incoming !== null) void navigate(location.pathname, { replace: true, state: null })
  }, [incoming, navigate, location.pathname])

  const reference = useReferenceData(workspaceId)
  // A chave leva tudo o que muda o resultado: trocar de workspace, de página ou de filtro volta a "carregando" e descarta a resposta atrasada.
  const list = useResource(`transactions:${workspaceId}:${page}:${JSON.stringify(filters)}`, () => listTransactions(workspaceId, page, filters))

  // Uma página além do fim (a lista encolheu em outro aparelho): cai na última que existe.
  const beyond = list.status === 'ready' && list.data.items.length === 0 && list.data.totalPages > 0 && list.data.page > list.data.totalPages
  const lastPage = list.status === 'ready' ? list.data.totalPages : 1
  useEffect(() => {
    if (beyond) setPage(lastPage)
  }, [beyond, lastPage, setPage])

  // Ao trocar de página, o foco vai ao título da lista (nunca fica num botão que sumiu).
  const heading = useRef<HTMLHeadingElement>(null)
  const lastSeen = useRef(page)
  useEffect(() => {
    if (lastSeen.current === page) return
    lastSeen.current = page
    heading.current?.focus()
  }, [page])

  const loading = reference.status === 'loading' || list.status === 'loading'
  const failed = reference.status === 'error' || list.status === 'error'

  return (
    <>
      <PageHeader
        title="Lançamentos"
        context={reference.status === 'ready' && list.status === 'ready' && !beyond && list.data.items.length > 0 ? `${list.data.totalItems} ${list.data.totalItems === 1 ? 'lançamento' : 'lançamentos'}` : undefined}
        action={canWrite ? <ButtonLink variant="primary" to="/lancamentos/novo">Novo lançamento</ButtonLink> : undefined}
      />

      {notice ? <Alert tone="info">{notice}</Alert> : null}

      <div className="list-toolbar">
        <Button
          id="transaction-filters-button"
          variant="secondary"
          icon="filter"
          aria-haspopup="dialog"
          disabled={reference.status !== 'ready'}
          onClick={() => setFiltersOpen(true)}
        >
          {activeCount > 0 ? `Filtros (${activeCount})` : 'Filtros'}
        </Button>
      </div>

      {reference.status === 'ready' && filtersOpen ? (
        <TransactionFilters
          reference={reference}
          onDone={() => {
            setFiltersOpen(false)
            document.getElementById('transaction-filters-button')?.focus()
          }}
        />
      ) : null}

      {reference.status === 'ready' && activeCount > 0 ? (
        <ul className="chips" aria-label="Filtros ativos">
          {summarize(filters, reference.names).map((chip) => (
            <li key={chip.key}>
              <Badge tone="brand">{chip.label}</Badge>
            </li>
          ))}
        </ul>
      ) : null}

      <h2 ref={heading} tabIndex={-1} className="sr-only list-heading">
        Lista de lançamentos
      </h2>

      {loading && !failed ? <LoadingNote>Carregando lançamentos…</LoadingNote> : null}

      {failed ? (
        <ErrorNotice
          onRetry={() => {
            if (reference.status === 'error') reference.reload()
            if (list.status === 'error') list.reload()
          }}
        >
          Não foi possível carregar os lançamentos. Tente de novo.
        </ErrorNotice>
      ) : null}

      {reference.status === 'ready' && list.status === 'ready' && !beyond ? (
        list.data.items.length === 0 ? (
          <Card>
            <div className="stack stack--tight">
              <p className="title">{activeCount > 0 ? 'Nenhum lançamento encontrado' : 'Nenhum lançamento ainda'}</p>
              <p className="text-body muted">
                {activeCount > 0
                  ? 'Nenhum lançamento atende aos filtros escolhidos.'
                  : canWrite
                    ? 'Registre o primeiro lançamento para começar a acompanhar o seu dinheiro.'
                    : 'Quem pode editar este workspace registra os lançamentos.'}
              </p>
              {activeCount > 0 ? (
                <div>
                  <Button variant="secondary" onClick={clear}>
                    Limpar filtros
                  </Button>
                </div>
              ) : null}
            </div>
          </Card>
        ) : (
          <>
            {groupByDay(list.data.items.map((tx) => toDisplayRow(tx, reference.names))).map((group) => (
              <section key={`${group.day}-${group.rows[0].id}`} className="day-group" aria-label={formatDay(group.day)}>
                <h3 className="text-label muted day-group__title">{formatDay(group.day)}</h3>
                <ul className="transaction-list">
                  {group.rows.map((row) => (
                    <li key={row.id}>
                      <TransactionRow
                        description={row.description}
                        cents={row.cents}
                        kind={row.kind}
                        account={row.account}
                        category={row.category}
                        categoryColor={row.categoryColor}
                        categoryIcon={row.categoryIcon}
                        pending={row.pending}
                        attention={row.pending ? 'Pendente' : undefined}
                        tags={row.tags}
                        onClick={() => void navigate(`/lancamentos/${row.id}`)}
                      />
                    </li>
                  ))}
                </ul>
              </section>
            ))}
            <Pager page={list.data.page} totalPages={list.data.totalPages} onPage={setPage} />
          </>
        )
      ) : null}

      {/* As folhas de criar e editar são rotas filhas: a lista fica atrás, e `changed` a atualiza. */}
      {reference.status === 'ready' ? <Outlet context={{ workspaceId, reference, changed: list.reload } satisfies SheetContext} /> : null}
    </>
  )
}
