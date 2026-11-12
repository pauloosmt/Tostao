import type { Transaction } from '../api/types'
import { Badge, Card, Money, PageHeader } from '../design-system/src'
import { formatDate } from '../dates/dates'
import { ButtonLink } from '../ui/ButtonLink'
import type { ReferenceData } from './reference'
import { tagsOf, toDisplayRow } from './rows'
import { transferEnds } from '../validation/transactionForm'

const TYPE_LABEL: Record<string, string> = { EXPENSE: 'Despesa', INCOME: 'Receita', TRANSFER: 'Transferência' }

export interface TransactionDetailsProps {
  transaction: Transaction
  reference: ReferenceData
  title: string
}

/** O lançamento só para leitura (o leitor consulta; não há formulário nem ações). */
export function TransactionDetails({ transaction, reference, title }: TransactionDetailsProps) {
  const row = toDisplayRow(transaction, reference.names)
  const transfer = transaction.type === 'TRANSFER'
  const ends = transferEnds(transaction)
  const tags = tagsOf(transaction)

  return (
    <>
      <PageHeader title={title} />
      <Card>
        <div className="stack">
          <p className="text-body-sm muted">{row.description}</p>
          <Money cents={row.cents} kind={row.kind} size="xl" />
          <dl className="profile-data">
            <dt className="text-label muted">Tipo</dt>
            <dd className="text-body">{TYPE_LABEL[transaction.type] ?? 'Lançamento'}</dd>
            <dt className="text-label muted">Data</dt>
            <dd className="text-body">{formatDate(transaction.occurredOn)}</dd>
            {transfer ? (
              <>
                <dt className="text-label muted">De</dt>
                <dd className="text-body">{reference.names.accountName(ends.origin)}</dd>
                <dt className="text-label muted">Para</dt>
                <dd className="text-body">{reference.names.accountName(ends.destination)}</dd>
              </>
            ) : (
              <>
                <dt className="text-label muted">Conta</dt>
                <dd className="text-body">{row.account}</dd>
                <dt className="text-label muted">Categoria</dt>
                <dd className="text-body">{row.category}</dd>
              </>
            )}
            <dt className="text-label muted">Estado</dt>
            <dd className="text-body">{row.pending ? 'Pendente' : 'Efetivado'}</dd>
            {transaction.notes ? (
              <>
                <dt className="text-label muted">Observações</dt>
                <dd className="text-body">{transaction.notes}</dd>
              </>
            ) : null}
          </dl>
          {tags.length > 0 ? (
            <ul className="chips" aria-label="Origem do lançamento">
              {tags.map((tag) => (
                <li key={tag}>
                  <Badge tone="neutral">{tag}</Badge>
                </li>
              ))}
            </ul>
          ) : null}
          <div>
            <ButtonLink to="/lancamentos">Voltar aos lançamentos</ButtonLink>
          </div>
        </div>
      </Card>
    </>
  )
}
