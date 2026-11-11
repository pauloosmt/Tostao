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
