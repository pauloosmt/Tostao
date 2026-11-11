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
