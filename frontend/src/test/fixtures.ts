// Dados de teste: a pessoa com um papel no workspace, contas e categorias.
import type { Account, BalanceReport, Category, CategoryTree, Me, Role } from '../api/types'

/** O workspace atual dos testes de tela. */
export const WID = 'w-casa'

export function makeMe(role: Role = 'OWNER', extra: Partial<Me> = {}): Me {
  return {
    id: 'u-ana',
    name: 'Ana Maria',
    email: 'ana@exemplo.com',
    workspaces: [{ id: WID, name: 'Casa', baseCurrency: 'BRL', role }],
    ...extra,
  }
}

let sequence = 0

export function makeAccount(extra: Partial<Account> = {}): Account {
  sequence += 1
  return {
    id: `acc-${sequence}`,
    name: `Conta ${sequence}`,
    type: 'CHECKING',
    currency: 'BRL',
    initialBalanceCents: 0,
    balanceCents: 0,
    creditLimitCents: null,
    closingDay: null,
    dueDay: null,
    archived: false,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...extra,
  }
}

/** O relatório de saldo coerente com as contas dadas: só as ativas, e o total é a soma delas. */
export function makeBalanceReport(accounts: Account[]): BalanceReport {
  const active = accounts.filter((account) => !account.archived)
  return {
    accounts: active.map(({ id, name, type, balanceCents }) => ({ id, name, type, balanceCents })),
    totalBalanceCents: active.reduce((sum, account) => sum + account.balanceCents, 0),
  }
}

export function makeCategory(extra: Partial<Category> = {}): Category {
  sequence += 1
  return {
    id: `cat-${sequence}`,
    name: `Categoria ${sequence}`,
    kind: 'EXPENSE',
    parentId: null,
    color: null,
    icon: null,
    archived: false,
    createdAt: '2026-01-01T00:00:00Z',
    ...extra,
  }
}

export function makeCategoryTree(extra: Partial<Category> = {}, subcategories: Category[] = []): CategoryTree {
  const parent = makeCategory(extra)
  return { ...parent, subcategories: subcategories.map((sub) => ({ ...sub, parentId: parent.id, kind: parent.kind })) }
}
