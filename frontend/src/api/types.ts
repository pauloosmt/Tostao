// Os tipos da API (espelham o contrato da spec 001).

export type Role = 'OWNER' | 'EDITOR' | 'VIEWER'

export interface WorkspaceMembership {
  id: string
  name: string
  baseCurrency: string
  role: Role
}

export interface Me {
  id: string
  name: string
  email: string
  workspaces: WorkspaceMembership[]
}

/** Um erro de campo do Problem Details (`errors[]`). */
export interface FieldError {
  field: string
  message: string
}

export interface Problem {
  type?: string
  title?: string
  status?: number
  detail?: string
  errors?: FieldError[]
}

// ---- Contas (spec 002) ----

export type AccountType = 'CHECKING' | 'SAVINGS' | 'CASH' | 'CREDIT_CARD' | 'INVESTMENT'

export interface Account {
  id: string
  name: string
  type: AccountType
  currency: string
  /** Em centavos inteiros. */
  initialBalanceCents: number
  balanceCents: number
  /** Só nos cartões de crédito. */
  creditLimitCents: number | null
  closingDay: number | null
  dueDay: number | null
  archived: boolean
  createdAt: string
  updatedAt: string
}

export interface CreateAccount {
  name: string
  type: AccountType
  initialBalanceCents: number
  creditLimitCents?: number
  closingDay?: number
  dueDay?: number
}

/** O tipo não é enviado na edição: ele não muda. */
export interface UpdateAccount {
  name: string
  initialBalanceCents: number
  creditLimitCents?: number
  closingDay?: number
  dueDay?: number
}

export interface BalanceReport {
  accounts: { id: string; name: string; type: AccountType; balanceCents: number }[]
  totalBalanceCents: number
}

// ---- Categorias (spec 003) ----

export type CategoryKind = 'INCOME' | 'EXPENSE'

export interface Category {
  id: string
  name: string
  kind: CategoryKind
  parentId: string | null
  /** Cor no formato `#RRGGBB`. */
  color: string | null
  icon: string | null
  archived: boolean
  createdAt: string
}

export interface CategoryTree extends Category {
  subcategories: Category[]
}

/** Principal: com `kind`. Subcategoria: com `parentId` (e sem `kind`: a API usa o da principal). */
export interface CreateCategory {
  name: string
  kind?: CategoryKind
  parentId?: string
  color?: string
  icon?: string
}

/** `kind` e `parentId` não são enviados: não mudam. A API grava `color` e `icon` como vierem (`null` se omitidos). */
export interface UpdateCategory {
  name: string
  color?: string
  icon?: string
}

// ---- Lançamentos (specs 004 e 009) ----

export type TransactionType = 'INCOME' | 'EXPENSE' | 'TRANSFER'
export type TransactionStatus = 'CLEARED' | 'PENDING'

export interface Transaction {
  id: string
  accountId: string
  type: TransactionType
  /** Em centavos inteiros e COM sinal: receita e entrada positivas, despesa e saída negativas. */
  amountCents: number
  /** `AAAA-MM-DD`, sem fuso. */
  occurredOn: string
  description: string
  notes: string | null
  categoryId: string | null
  /** Só nas transferências: liga as duas pontas e diz a conta da outra. */
  transferGroupId: string | null
  counterpartAccountId: string | null
  createdBy: string
  createdAt: string
  updatedAt: string
  status: TransactionStatus
  installmentGroupId: string | null
  installmentNumber: number | null
  installmentCount: number | null
  autoClear: boolean
  recurrenceId: string | null
  occurrenceNumber: number | null
  imported: boolean
}

export interface TransactionPage {
  items: Transaction[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

/** O valor vai sempre POSITIVO (o sinal vem do tipo). */
export interface CreateTransaction {
  type: TransactionType
  accountId: string
  /** Só em `TRANSFER`. */
  destinationAccountId?: string
  amountCents: number
  occurredOn: string
  description: string
  notes?: string
  /** Obrigatório em receita e despesa; proibido na transferência. */
  categoryId?: string
  /** Ausente = efetivado. */
  status?: 'PENDING'
}

/** O tipo não é enviado: não muda. A edição é completa (um campo opcional ausente limpa o valor). */
export interface UpdateTransaction {
  accountId: string
  destinationAccountId?: string
  amountCents: number
  occurredOn: string
  description: string
  notes?: string
  categoryId?: string
  status: 'PENDING' | 'CLEARED'
}

// ---- Painel do mês (spec 005) ----

export interface DashboardTotals {
  incomeCents: number
  expenseCents: number
  /** Receitas menos despesas. */
  resultCents: number
}

export interface DashboardCategoryItem {
  categoryId: string
  name: string
  totalCents: number
  /** Percentual sobre o total do tipo, com duas casas. */
  percent: number
}

/** Categoria principal; `categoryId` nulo é "Sem categoria". */
export interface DashboardCategoryGroup {
  categoryId: string | null
  name: string
  directCents: number
  totalCents: number
  percent: number
  children: DashboardCategoryItem[]
}

export interface DashboardChange {
  previousCents: number
  changeCents: number
  /** Nulo quando o período anterior é zero. */
  changePercent: number | null
}

export interface DashboardAccountBalance {
  id: string
  name: string
  type: AccountType
  balanceCents: number
}

export interface Dashboard {
  period: { type: 'MONTH' | 'WEEK'; id: string; startDate: string; endDate: string }
  totals: DashboardTotals
  incomeByCategory: DashboardCategoryGroup[]
  expenseByCategory: DashboardCategoryGroup[]
  comparison: { previousPeriod: { id: string }; income: DashboardChange; expense: DashboardChange; result: DashboardChange }
  balances: { accounts: DashboardAccountBalance[]; totalBalanceCents: number }
  /** O pendente do período: informativo, nunca entra nos totais. */
  pending: { incomeCents: number; expenseCents: number; count: number }
}

// ---- Orçamentos mensais (spec 007) ----

export type BudgetStatus = 'WITHIN' | 'WARNING' | 'EXCEEDED'

/** Um limite com o gasto do mês; `categoryId` nulo é o limite geral (todas as despesas). */
export interface BudgetItem {
  id: string
  categoryId: string | null
  categoryName: string | null
  categoryArchived: boolean
  amountCents: number
  spentCents: number
  remainingCents: number
  /** Percentual do limite já gasto (pode passar de 100); nulo no limite zero. */
  percentUsed: number | null
  status: BudgetStatus
}

export interface MonthBudget {
  month: { id: string; startDate: string; endDate: string }
  totalSpentCents: number
  /** O gasto que nenhum limite de categoria cobre. */
  spentOutsideCategoryLimitsCents: number
  budgets: BudgetItem[]
}

export interface MonthlyBudget {
  id: string
  month: string
  categoryId: string | null
  categoryName: string | null
  amountCents: number
}

export interface CopyBudgetsResult {
  created: number
  skippedExisting: number
  skippedArchivedCategory: number
}

// ---- Cartão de crédito e faturas (spec 008) ----

export type InvoiceStatus = 'OPEN' | 'CLOSED' | 'PAID' | 'OVERDUE'

export interface OpenInvoice {
  month: string
  periodStart: string
  closingDate: string
  dueDate: string
  totalCents: number
}

export interface CardSummary {
  accountId: string
  name: string
  archived: boolean
  /** Nulo: cartão sem limite cadastrado. */
  limitCents: number | null
  /** O saldo negativo da conta (zero quando o saldo não é negativo). */
  owedCents: number
  /** O limite menos o devido (negativo se passou do limite); nulo sem limite. */
  availableCents: number | null
  /** Nula quando o cartão não tem os dois dias cadastrados. */
  openInvoice: OpenInvoice | null
}

/** Um lançamento da fatura: `chargeCents` positivo é compra ou saque, negativo é estorno. */
export interface InvoiceLine {
  id: string
  date: string
  description: string
  categoryId: string | null
  categoryName: string | null
  type: string
  chargeCents: number
}

export interface Invoice {
  month: string
  periodStart: string
  closingDate: string
  dueDate: string
  totalCents: number
  paidCents: number
  remainingCents: number
  status: InvoiceStatus
  lines: InvoiceLine[]
}
