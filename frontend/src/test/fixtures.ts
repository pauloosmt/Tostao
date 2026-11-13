// Dados de teste: a pessoa com um papel no workspace, contas e categorias.
import type { Account, BalanceReport, BudgetItem, CardSummary, Category, CategoryTree, Dashboard, ImportLinePreview, ImportPreview, InstallmentPlan, Invitation, Invoice, Me, Member, MonthBudget, Recurrence, RecurrenceDetail, Role, Transaction, WeekBudget } from '../api/types'

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

/** O plano de uma compra parcelada com as parcelas dadas (as não excluídas); os totais saem delas. */
export function makeInstallmentPlan(installments: Transaction[], extra: Partial<InstallmentPlan> = {}): InstallmentPlan {
  const value = (t: Transaction) => Math.abs(t.amountCents)
  return {
    groupId: 'g-plano',
    description: 'Geladeira',
    installmentCount: installments.length,
    accountId: 'a-carteira',
    categoryId: 'c-mercado',
    totalCents: installments.reduce((sum, t) => sum + value(t), 0),
    clearedCents: installments.filter((t) => t.status === 'CLEARED').reduce((sum, t) => sum + value(t), 0),
    pendingCents: installments.filter((t) => t.status === 'PENDING').reduce((sum, t) => sum + value(t), 0),
    installments,
    ...extra,
  }
}

export function makeRecurrence(extra: Partial<Recurrence> = {}): Recurrence {
  sequence += 1
  return {
    id: `rec-${sequence}`,
    accountId: 'a-carteira',
    destinationAccountId: null,
    categoryId: 'c-mercado',
    type: 'EXPENSE',
    amountCents: 120000,
    description: `Aluguel ${sequence}`,
    notes: null,
    frequency: 'MONTHLY',
    startOn: '2026-11-10',
    endOn: null,
    autoClear: false,
    ended: false,
    endedAt: null,
    nextOccurrenceOn: '2026-12-10',
    createdBy: 'u-ana',
    createdAt: '2026-11-01T10:00:00Z',
    ...extra,
  }
}

/** A recorrência com as ocorrências dadas; os totais saem delas. */
export function makeRecurrenceDetail(occurrences: Transaction[] = [], extra: Partial<RecurrenceDetail> = {}): RecurrenceDetail {
  const value = (t: Transaction) => Math.abs(t.amountCents)
  return {
    ...makeRecurrence(),
    totalCents: occurrences.reduce((sum, t) => sum + value(t), 0),
    clearedCents: occurrences.filter((t) => t.status === 'CLEARED').reduce((sum, t) => sum + value(t), 0),
    pendingCents: occurrences.filter((t) => t.status === 'PENDING').reduce((sum, t) => sum + value(t), 0),
    occurrences,
    ...extra,
  }
}

export function makeMember(extra: Partial<Member> = {}): Member {
  sequence += 1
  return { userId: `u-${sequence}`, name: `Pessoa ${sequence}`, email: `pessoa${sequence}@exemplo.com`, role: 'EDITOR', joinedAt: '2026-10-05T12:00:00Z', ...extra }
}

export function makeInvitation(extra: Partial<Invitation> = {}): Invitation {
  sequence += 1
  return { id: `inv-${sequence}`, email: `convidada${sequence}@exemplo.com`, role: 'VIEWER', createdBy: 'u-ana', createdAt: '2026-11-10T12:00:00Z', expiresAt: '2026-11-24T12:00:00Z', ...extra }
}

export function makeImportLine(extra: Partial<ImportLinePreview> = {}): ImportLinePreview {
  sequence += 1
  return {
    line: sequence,
    occurredOn: '2026-11-03',
    description: `Movimento ${sequence}`,
    amountCents: -3590,
    type: 'EXPENSE',
    key: `k-${sequence}`,
    existing: false,
    possibleDuplicate: false,
    suggestedCategoryId: null,
    problems: [],
    ...extra,
  }
}

/** A prévia coerente com as linhas dadas: as contagens saem delas. */
export function makeImportPreview(lines: ImportLinePreview[] = [], extra: Partial<ImportPreview> = {}): ImportPreview {
  const invalid = lines.filter((line) => line.problems.length > 0).length
  const existing = lines.filter((line) => line.problems.length === 0 && line.existing).length
  return { format: 'CSV', total: lines.length, valid: lines.length - invalid - existing, existing, invalid, lines, ...extra }
}
