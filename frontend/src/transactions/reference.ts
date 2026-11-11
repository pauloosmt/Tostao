// Os nomes e as opções de conta e categoria que a lista e o formulário de lançamentos precisam. Carregados uma vez por tela, com as
// arquivadas (um lançamento antigo pode apontar para uma conta ou categoria arquivada, e o nome dela tem de aparecer).
import { useMemo } from 'react'
import { listAccounts } from '../api/accounts'
import { listCategories } from '../api/categories'
import type { Account, CategoryKind, CategoryTree } from '../api/types'
import { useResource } from '../data/useResource'
import type { Names } from './rows'

export interface Option {
  value: string
  label: string
}

export interface ReferenceData {
  names: Names
  /** As contas ativas, para escolher. */
  accountOptions: (currentId?: string) => Option[]
  /** As categorias ativas do tipo (a principal e, sob ela, "Principal › Sub"), para escolher. */
  categoryOptions: (kind: CategoryKind, currentId?: string) => Option[]
  /** Todas as contas, inclusive as arquivadas (para filtrar lançamentos antigos). */
  accountFilterOptions: () => Option[]
  /** Todas as categorias, inclusive as arquivadas, com o tipo no rótulo ("Despesa · Alimentação › Mercado"). */
  categoryFilterOptions: () => Option[]
  /** Se a categoria é ativa e do tipo (para descartar a escolhida ao trocar de tipo). */
  categoryFits: (categoryId: string, kind: CategoryKind) => boolean
}

const ARCHIVED = ' (arquivada)'
const REMOVED_ACCOUNT = 'Conta removida'

/** Monta tudo a partir das contas e categorias (com as arquivadas). Puro. */
export function buildReference(accounts: Account[], categories: CategoryTree[]): ReferenceData {
  const accountById = new Map(accounts.map((account) => [account.id, account]))
  const categoryLabel = new Map<string, string>()
  const categoryInfo = new Map<string, { kind: CategoryKind; archived: boolean }>()

  for (const principal of categories) {
    categoryLabel.set(principal.id, principal.name + (principal.archived ? ARCHIVED : ''))
    categoryInfo.set(principal.id, { kind: principal.kind, archived: principal.archived })
    for (const sub of principal.subcategories) {
      categoryLabel.set(sub.id, `${principal.name} › ${sub.name}${sub.archived ? ARCHIVED : ''}`)
      categoryInfo.set(sub.id, { kind: principal.kind, archived: sub.archived })
    }
  }

  const accountLabel = (account: Account) => account.name + (account.archived ? ARCHIVED : '')

  return {
    names: {
      accountName: (id) => {
        const account = accountById.get(id)
        return account ? accountLabel(account) : REMOVED_ACCOUNT
      },
      categoryName: (id) => categoryLabel.get(id) ?? null,
    },
    accountOptions: (currentId) =>
      accounts
        .filter((account) => !account.archived || account.id === currentId)
        .map((account) => ({ value: account.id, label: accountLabel(account) })),
    categoryOptions: (kind, currentId) => {
      const options: Option[] = []
      for (const principal of categories) {
        if (principal.kind !== kind) continue
        if (!principal.archived || principal.id === currentId) options.push({ value: principal.id, label: categoryLabel.get(principal.id) as string })
        for (const sub of principal.subcategories) {
          if (!sub.archived || sub.id === currentId) options.push({ value: sub.id, label: categoryLabel.get(sub.id) as string })
        }
      }
      return options
    },
    accountFilterOptions: () => accounts.map((account) => ({ value: account.id, label: accountLabel(account) })),
    categoryFilterOptions: () =>
      categories.flatMap((principal) =>
        [principal.id, ...principal.subcategories.map((sub) => sub.id)].map((id) => ({
          value: id,
          label: `${principal.kind === 'EXPENSE' ? 'Despesa' : 'Receita'} · ${categoryLabel.get(id) as string}`,
        })),
      ),
    categoryFits: (categoryId, kind) => {
      const info = categoryInfo.get(categoryId)
      return info !== undefined && info.kind === kind && !info.archived
    },
  }
}

export type Reference = { status: 'loading' } | { status: 'error'; reload: () => void } | ({ status: 'ready'; reload: () => void } & ReferenceData)

/** Carrega as contas e as categorias do workspace (com as arquivadas); trocar de workspace volta a "carregando" e descarta a resposta atrasada. */
export function useReferenceData(workspaceId: string): Reference {
  const resource = useResource(`reference:${workspaceId}`, () => Promise.all([listAccounts(workspaceId, true), listCategories(workspaceId, true)]))
  const data = resource.status === 'ready' ? resource.data : null
  const built = useMemo(() => (data ? buildReference(data[0], data[1]) : null), [data])

  if (resource.status === 'loading') return { status: 'loading' }
  if (resource.status === 'error' || built === null) return { status: 'error', reload: resource.reload }
  return { status: 'ready', reload: resource.reload, ...built }
}
