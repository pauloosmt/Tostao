import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { listTransactions } from '../api/transactions'
import { Alert, Button, Card, TransactionRow } from '../design-system/src'
import { useResource } from '../data/useResource'
import { formatDay } from '../dates/dates'
import { usePermissions } from '../permissions/usePermissions'
import { ButtonLink } from '../ui/ButtonLink'
import { ErrorNotice } from '../ui/ErrorNotice'
import { LoadingNote } from '../ui/LoadingNote'
import { useWorkspace } from '../workspace/useWorkspace'
import { Pager } from './Pager'
import { useReferenceData } from './reference'
import { groupByDay, toDisplayRow } from './rows'
import { useTransactionFilters } from './useFilters'

export function TransactionsPage() {
  const { current } = useWorkspace()

  return (
    <>
      <div className="page-header">
        <h1 className="heading-1">Lançamentos</h1>
      </div>
      {current ? <TransactionsOf workspaceId={current.id} /> : <Card><p className="text-body">Você não participa de nenhum workspace.</p></Card>}
    </>
  )
}

function TransactionsOf({ workspaceId }: { workspaceId: string }) {
  const { canWrite } = usePermissions()
  const navigate = useNavigate()
  const location = useLocation()
  const { filters, page, activeCount, clear, setPage } = useTransactionFilters()

  // O aviso do que acabou de acontecer (o lançamento foi registrado) vem no estado do roteador, é lido uma vez e tirado do histórico.
  const [notice] = useState(() => (location.state as { notice?: string } | null)?.notice ?? null)
  useEffect(() => {
    if (notice !== null) void navigate(location.pathname, { replace: true, state: null })
  }, [notice, navigate, location.pathname])

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
      {notice ? <Alert tone="info">{notice}</Alert> : null}

      <div className="list-toolbar">
        <span />
        {canWrite ? <ButtonLink variant="primary" to="/lancamentos/novo">Novo lançamento</ButtonLink> : null}
      </div>

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
            <p className="text-body-sm muted">
              {list.data.totalItems} {list.data.totalItems === 1 ? 'lançamento' : 'lançamentos'}
            </p>
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
                        pending={row.pending}
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
    </>
  )
}
