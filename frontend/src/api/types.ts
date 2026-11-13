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

/** O que a API devolve do mês (`month`) e da semana (`week`); a tela usa o formato único `PeriodBudget`. */
export interface MonthBudget {
  month: { id: string; startDate: string; endDate: string }
  totalSpentCents: number
  /** O gasto que nenhum limite de categoria cobre. */
  spentOutsideCategoryLimitsCents: number
  budgets: BudgetItem[]
}

export interface WeekBudget {
  week: { id: string; startDate: string; endDate: string }
  totalSpentCents: number
  spentOutsideCategoryLimitsCents: number
  budgets: BudgetItem[]
}

export interface PeriodBudget {
  period: { id: string; startDate: string; endDate: string }
  totalSpentCents: number
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

// ---- Compras parceladas (spec 010) ----

export interface CreateInstallmentPurchase {
  accountId: string
  categoryId: string
  totalCents: number
  /** De 2 a 48. */
  installmentCount: number
  /** `AAAA-MM-DD`: a data da primeira parcela; as outras vêm mês a mês. */
  firstOccurredOn: string
  /** A descrição base: cada parcela recebe o sufixo " (N/T)". */
  description: string
  notes?: string
  /** Ausente: a primeira parcela fica efetivada. */
  firstInstallmentStatus?: 'CLEARED' | 'PENDING'
}

export interface InstallmentPlan {
  groupId: string
  /** A descrição base, sem o sufixo da parcela. */
  description: string
  /** O total original de parcelas, mesmo que alguma tenha sido excluída. */
  installmentCount: number
  accountId: string
  categoryId: string
  totalCents: number
  clearedCents: number
  pendingCents: number
  installments: Transaction[]
}

export interface CancelInstallmentsResult {
  cancelled: number
}

// ---- Recorrências (specs 011 e 014) ----

export type RecurrenceFrequency = 'WEEKLY' | 'MONTHLY' | 'YEARLY'

export interface Recurrence {
  id: string
  accountId: string
  /** Só na transferência. */
  destinationAccountId: string | null
  categoryId: string | null
  type: TransactionType
  /** Sempre positivo, em centavos: o sinal vem do tipo. */
  amountCents: number
  description: string
  notes: string | null
  frequency: RecurrenceFrequency
  startOn: string
  endOn: string | null
  autoClear: boolean
  ended: boolean
  endedAt: string | null
  /** A menor data entre as ocorrências pendentes de hoje em diante; nulo se não há. */
  nextOccurrenceOn: string | null
  createdBy: string
  createdAt: string
}

/** Os totais são positivos e somam só as ocorrências da lista. */
export interface RecurrenceDetail extends Recurrence {
  totalCents: number
  clearedCents: number
  pendingCents: number
  occurrences: Transaction[]
}

export interface CreateRecurrence {
  accountId: string
  destinationAccountId?: string
  categoryId?: string
  type: TransactionType
  amountCents: number
  description: string
  notes?: string
  frequency: RecurrenceFrequency
  startOn: string
  endOn?: string
  autoClear?: boolean
}

export interface EndRecurrenceResult {
  cancelled: number
}

// ---- Membros e convites (spec 012) ----

export interface Member {
  userId: string
  name: string
  email: string
  role: Role
  joinedAt: string
}

export interface Invitation {
  id: string
  email: string
  role: Role
  createdBy: string
  createdAt: string
  expiresAt: string
}

/** O convite recém-criado: a única resposta com o `code` (que a API nunca guarda nem devolve de novo). */
export interface InvitationCreated extends Invitation {
  code: string
}

export interface AcceptedInvitation {
  workspaceId: string
  name: string
  role: Role
}

// ---- Importação de extratos (spec 013) ----

export interface ImportLinePreview {
  /** O número da linha no arquivo. */
  line: number
  occurredOn: string | null
  description: string
  /** Com sinal: entrada positiva, saída negativa. Nulo quando não deu para ler. */
  amountCents: number | null
  type: TransactionType | null
  /** A chave que identifica o movimento na conta; nula quando a linha tem problema. */
  key: string | null
  /** A chave já existe na conta (inclusive em lançamento excluído). */
  existing: boolean
  /** Há na conta um lançamento sem chave com a mesma data e o mesmo valor: só um aviso. */
  possibleDuplicate: boolean
  /** A categoria do último lançamento com a mesma descrição, se houver. */
  suggestedCategoryId: string | null
  problems: string[]
}

export interface ImportPreview {
  format: 'OFX' | 'CSV' | 'PDF'
  total: number
  valid: number
  existing: number
  invalid: number
  lines: ImportLinePreview[]
}

/** Uma linha da confirmação, como veio da prévia. */
export interface ImportLineInput {
  key: string
  occurredOn: string
  description: string
  amountCents: number
  categoryId?: string
}

export interface ImportConfirmResult {
  created: Transaction[]
  /** Quantas linhas já existiam na conta e foram ignoradas. */
  skipped: number
}
