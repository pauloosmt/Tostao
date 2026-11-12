import { useEffect, useState } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router'
import { listRecurrences } from '../api/recurrences'
import { useResource } from '../data/useResource'
import { formatDate } from '../dates/dates'
import { Alert, Card, PageHeader, TransactionRow } from '../design-system/src'
import { usePermissions } from '../permissions/usePermissions'
import { ButtonLink } from '../ui/ButtonLink'
import { ErrorNotice } from '../ui/ErrorNotice'
import { LoadingNote } from '../ui/LoadingNote'
import { useReferenceData } from '../transactions/reference'
import { useWorkspace } from '../workspace/useWorkspace'
import type { RecurrenceSheetContext } from './sheetContext'
import { frequencyWord } from '../validation/recurrenceForm'
import { sortRecurrences } from './sortRecurrences'

const KIND = { INCOME: 'income', EXPENSE: 'expense', TRANSFER: 'transfer' } as const

export function RecurrencesPage() {
  const { current } = useWorkspace()
  return current ? (
    <RecurrencesOf workspaceId={current.id} />
  ) : (
    <>
      <PageHeader title="Recorrências" />
      <Card><p className="text-body">Você não participa de nenhum workspace.</p></Card>
    </>
  )
}

function RecurrencesOf({ workspaceId }: { workspaceId: string }) {
  const { canWrite } = usePermissions()
  const navigate = useNavigate()
  const location = useLocation()
  const reference = useReferenceData(workspaceId)
  // A chave leva o workspace: trocar volta a "carregando" e descarta a resposta que ficou para trás.
  const list = useResource(`recurrences:${workspaceId}`, () => listRecurrences(workspaceId))

  // O aviso do que acabou de acontecer chega no estado do roteador (a lista continua montada atrás da folha).
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

  const ready = reference.status === 'ready' && list.status === 'ready'
  const failed = reference.status === 'error' || list.status === 'error'
  const items = list.status === 'ready' ? sortRecurrences(list.data) : []

  return (
    <>
      <PageHeader
        title="Recorrências"
        context={ready && items.length > 0 ? `${items.length} ${items.length === 1 ? 'recorrência' : 'recorrências'}` : undefined}
        action={canWrite ? <ButtonLink variant="primary" to="/recorrencias/nova">Nova recorrência</ButtonLink> : undefined}
      />
      {notice ? <Alert tone="info">{notice}</Alert> : null}

      {!ready && !failed ? <LoadingNote>Carregando recorrências…</LoadingNote> : null}
      {failed ? (
        <ErrorNotice
          onRetry={() => {
            if (reference.status === 'error') reference.reload()
            if (list.status === 'error') list.reload()
          }}
        >
          Não foi possível carregar as recorrências. Tente de novo.
        </ErrorNotice>
      ) : null}

      {ready ? (
        items.length === 0 ? (
          <Card>
            <div className="stack stack--tight">
              <p className="title">Nenhuma recorrência ainda</p>
              <p className="text-body muted">
                {canWrite ? 'Cadastre o que se repete (aluguel, salário, assinatura) e os lançamentos nascem sozinhos nas datas.' : 'Quem pode editar este workspace cadastra as recorrências.'}
              </p>
            </div>
          </Card>
        ) : (
          <ul className="transaction-list" aria-label="Recorrências">
            {items.map((item) => {
              const transfer = item.type === 'TRANSFER'
              const names = reference.names
              const color = !transfer && item.categoryId ? names.categoryColor(item.categoryId) : null
              const next = item.ended ? 'Encerrada' : item.nextOccurrenceOn ? `Próxima: ${formatDate(item.nextOccurrenceOn)}` : 'Sem próximas ocorrências'
              return (
                <li key={item.id}>
                  <TransactionRow
                    description={item.description}
                    cents={item.amountCents}
                    kind={KIND[item.type] ?? 'expense'}
                    account={names.accountName(item.accountId)}
                    category={transfer ? `Para ${names.accountName(item.destinationAccountId ?? '')}` : ((item.categoryId ? names.categoryName(item.categoryId) : null) ?? 'Sem categoria')}
                    categoryColor={color}
                    categoryIcon={!transfer && item.categoryId ? names.categoryIcon(item.categoryId) : null}
                    date={`${frequencyWord(item.frequency)} · ${next}`}
                    tags={[...(item.autoClear ? ['Efetiva sozinha'] : []), ...(item.ended ? ['Encerrada'] : [])]}
                    onClick={() => void navigate(`/recorrencias/${item.id}`)}
                  />
                </li>
              )
            })}
          </ul>
        )
      ) : null}

      {/* As folhas de criar e de detalhes são rotas filhas: a lista fica atrás, e `changed` a atualiza. */}
      {reference.status === 'ready' ? <Outlet context={{ workspaceId, reference, changed: list.reload } satisfies RecurrenceSheetContext} /> : null}
    </>
  )
}
