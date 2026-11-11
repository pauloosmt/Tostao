import type { FieldError } from './types'

export interface ApiErrorInit {
  status: number
  title: string
  detail?: string
  errors?: FieldError[]
  retryAfterSeconds?: number
}

/** A API respondeu com erro (Problem Details). */
export class ApiError extends Error {
  readonly status: number
  readonly title: string
  readonly detail: string | undefined
  readonly errors: FieldError[]
  /** Segundos até a próxima tentativa permitida (cabeçalho Retry-After do bloqueio por tentativas). */
  readonly retryAfterSeconds: number | undefined

  constructor(init: ApiErrorInit) {
    super(init.detail ?? init.title)
    this.name = 'ApiError'
    this.status = init.status
    this.title = init.title
    this.detail = init.detail
    this.errors = init.errors ?? []
    this.retryAfterSeconds = init.retryAfterSeconds
  }
}

/** Não houve resposta: sem rede, servidor inalcançável ou tempo esgotado. */
export class NetworkError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options)
    this.name = 'NetworkError'
  }
}
