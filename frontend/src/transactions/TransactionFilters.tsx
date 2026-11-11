import { useState, type FormEvent } from 'react'
import type { TransactionFilters as Filters } from '../api/transactions'
import type { TransactionStatus, TransactionType } from '../api/types'
import { Button, Card, Field } from '../design-system/src'
import { SelectField } from '../ui/SelectField'
import { validateFilters, type FilterField } from './filterSummary'
import type { ReferenceData } from './reference'
import { useTransactionFilters } from './useFilters'

interface Draft {
  from: string
  to: string
  accountId: string
  categoryId: string
  type: TransactionType | ''
  status: TransactionStatus | ''
  q: string
}

const draftOf = (filters: Filters): Draft => ({
  from: filters.from ?? '',
  to: filters.to ?? '',
  accountId: filters.accountId ?? '',
  categoryId: filters.categoryId ?? '',
  type: filters.type ?? '',
  status: filters.status ?? '',
  q: filters.q ?? '',
})

const filtersOf = (draft: Draft): Filters => ({
  from: draft.from.trim(),
  to: draft.to.trim(),
  accountId: draft.accountId,
  categoryId: draft.categoryId,
  type: draft.type === '' ? undefined : draft.type,
  status: draft.status === '' ? undefined : draft.status,
  q: draft.q,
})

const TYPE_OPTIONS = [
  { value: 'EXPENSE', label: 'Despesa' },
  { value: 'INCOME', label: 'Receita' },
  { value: 'TRANSFER', label: 'Transferência' },
]
const STATUS_OPTIONS = [
  { value: 'CLEARED', label: 'Efetivado' },
  { value: 'PENDING', label: 'Pendente' },
]
const FIELD_ID: Record<FilterField, string> = { from: 'filters-from', to: 'filters-to', q: 'filters-q' }
const FIELD_ORDER: readonly FilterField[] = ['from', 'to', 'q']

export interface TransactionFiltersProps {
  id: string
  reference: ReferenceData
  /** Chamado ao aplicar ou limpar: quem abriu o painel o fecha e devolve o foco. */
  onDone: () => void
}

/** O painel de filtros: o rascunho é local; só "Aplicar" muda a lista (e volta à primeira página). */
export function TransactionFilters({ id, reference, onDone }: TransactionFiltersProps) {
  const { filters, apply, clear } = useTransactionFilters()
  const [draft, setDraft] = useState<Draft>(() => draftOf(filters))
  const [errors, setErrors] = useState<Partial<Record<FilterField, string>>>({})

  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => setDraft((current) => ({ ...current, [key]: value }))

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    const found = validateFilters(draft)
    setErrors(found)
    const first = FIELD_ORDER.find((field) => found[field] !== undefined)
    if (first) {
      document.getElementById(FIELD_ID[first])?.focus()
      return
    }
    apply(filtersOf(draft))
    onDone()
  }

  function clearAll() {
    clear()
    onDone()
  }

  return (
    <Card>
      <form id={id} className="stack" aria-label="Filtros" onSubmit={onSubmit} noValidate>
        <div className="form-row">
          <Field id={FIELD_ID.from} label="De" type="date" value={draft.from} onChange={(event) => set('from', event.target.value)} error={errors.from} />
          <Field id={FIELD_ID.to} label="Até" type="date" value={draft.to} onChange={(event) => set('to', event.target.value)} error={errors.to} />
        </div>
        <div className="form-row">
          <SelectField label="Conta" placeholder="Todas" options={reference.accountFilterOptions()} value={draft.accountId} onChange={(event) => set('accountId', event.target.value)} />
          <SelectField label="Categoria" placeholder="Todas" options={reference.categoryFilterOptions()} value={draft.categoryId} onChange={(event) => set('categoryId', event.target.value)} />
        </div>
        <div className="form-row">
          <SelectField label="Tipo" placeholder="Todos" options={TYPE_OPTIONS} value={draft.type} onChange={(event) => set('type', event.target.value as Draft['type'])} />
          <SelectField label="Estado" placeholder="Todos" options={STATUS_OPTIONS} value={draft.status} onChange={(event) => set('status', event.target.value as Draft['status'])} />
        </div>
        <Field
          id={FIELD_ID.q}
          label="Buscar na descrição"
          autoComplete="off"
          value={draft.q}
          onChange={(event) => set('q', event.target.value)}
          error={errors.q}
        />
        <div className="form-actions">
          <Button variant="secondary" onClick={clearAll}>
            Limpar filtros
          </Button>
          <Button type="submit">Aplicar filtros</Button>
        </div>
      </form>
    </Card>
  )
}
