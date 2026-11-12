// Dados de teste: a pessoa com um papel no workspace, contas e categorias.
import type { Account, BalanceReport, BudgetItem, CardSummary, Category, CategoryTree, Dashboard, Invoice, Me, MonthBudget, Role, Transaction, WeekBudget } from '../api/types'

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

let transactionSequence = 0

export function makeTransaction(extra: Partial<Transaction> = {}): Transaction {
  transactionSequence += 1
  return {
    id: `tx-${transactionSequence}`,
    accountId: 'acc-1',
    type: 'EXPENSE',
    amountCents: -1000,
    occurredOn: '2026-11-11',
    description: `Lançamento ${transactionSequence}`,
    notes: null,
    categoryId: null,
    transferGroupId: null,
    counterpartAccountId: null,
    createdBy: 'u-ana',
    createdAt: '2026-01-01T00:00:00.000Z',
    updatedAt: '2026-01-01T00:00:00.000Z',
    status: 'CLEARED',
    installmentGroupId: null,
    installmentNumber: null,
    installmentCount: null,
    autoClear: false,
    recurrenceId: null,
    occurrenceNumber: null,
    imported: false,
    ...extra,
  }
}

/** O painel do mês coerente com as contas dadas (só as ativas); `extra` troca o que o teste quiser. */
export function makeDashboard(accounts: Account[] = [], extra: Partial<Dashboard> = {}): Dashboard {
  const active = accounts.filter((account) => !account.archived)
  const change = { previousCents: 0, changeCents: 0, changePercent: null }
  return {
    period: { type: 'MONTH', id: '2026-11', startDate: '2026-11-01', endDate: '2026-11-30' },
    totals: { incomeCents: 0, expenseCents: 0, resultCents: 0 },
    incomeByCategory: [],
    expenseByCategory: [],
    comparison: { previousPeriod: { id: '2026-10' }, income: change, expense: change, result: change },
    balances: {
      accounts: active.map(({ id, name, type, balanceCents }) => ({ id, name, type, balanceCents })),
      totalBalanceCents: active.reduce((sum, account) => sum + account.balanceCents, 0),
    },
    pending: { incomeCents: 0, expenseCents: 0, count: 0 },
    ...extra,
  }
}

/** Um limite do mês (por padrão, o de uma categoria de despesa); `extra` troca o que o teste quiser. */
export function makeBudgetItem(extra: Partial<BudgetItem> = {}): BudgetItem {
  sequence += 1
  return {
    id: `bud-${sequence}`,
    categoryId: `cat-${sequence}`,
    categoryName: `Categoria ${sequence}`,
    categoryArchived: false,
    amountCents: 100000,
    spentCents: 0,
    remainingCents: 100000,
    percentUsed: 0,
    status: 'WITHIN',
    ...extra,
  }
}

export function makeWeekBudget(budgets: BudgetItem[] = [], extra: Partial<WeekBudget> = {}): WeekBudget {
  return {
    week: { id: '2026-W46', startDate: '2026-11-09', endDate: '2026-11-15' },
    totalSpentCents: 0,
    spentOutsideCategoryLimitsCents: 0,
    budgets,
    ...extra,
  }
}

export function makeMonthBudget(budgets: BudgetItem[] = [], extra: Partial<MonthBudget> = {}): MonthBudget {
  return {
    month: { id: '2026-11', startDate: '2026-11-01', endDate: '2026-11-30' },
    totalSpentCents: 0,
    spentOutsideCategoryLimitsCents: 0,
    budgets,
    ...extra,
  }
}

export function makeCardSummary(extra: Partial<CardSummary> = {}): CardSummary {
  return {
    accountId: 'a-cartao',
    name: 'Nubank',
    archived: false,
    limitCents: 500000,
    owedCents: 120000,
    availableCents: 380000,
    openInvoice: { month: '2026-11', periodStart: '2026-10-11', closingDate: '2026-11-10', dueDate: '2026-11-17', totalCents: 120000 },
    ...extra,
  }
}

export function makeInvoice(extra: Partial<Invoice> = {}): Invoice {
  return {
    month: '2026-11',
    periodStart: '2026-10-11',
    closingDate: '2026-11-10',
    dueDate: '2026-11-17',
    totalCents: 120000,
    paidCents: 0,
    remainingCents: 120000,
    status: 'OPEN',
    lines: [],
    ...extra,
  }
}
