// Um erro da API (ou de rede) transformado no que a tela mostra: mensagens por campo, um aviso geral e se vale "Tentar de novo".
// É o único lugar que decide isso, para todo formulário e toda ação tratar o mesmo erro do mesmo jeito.
import { ApiError } from '../api/errors'

export const FAILURE_MESSAGES = {
  server: 'Não foi possível falar com o servidor. Tente de novo.',
  forbidden: 'Você não tem permissão para alterar isto neste workspace.',
  notFound: 'Não encontrada. A lista foi atualizada.',
  invalid: 'Não foi possível salvar. Confira os dados e tente de novo.',
  other: 'Não foi possível concluir a operação.',
  session: 'Sua sessão terminou. Entre de novo.',
} as const

export type FailureKind = 'validation' | 'conflict' | 'forbidden' | 'notFound' | 'session' | 'server' | 'other'

export interface Failure<F extends string> {
  kind: FailureKind
  /** O erro de cada campo da tela. */
  fields: Partial<Record<F, string>>
  /** O aviso geral (o que não é de um campo). */
  general?: string
  /** Vale oferecer "Tentar de novo" (erro do servidor ou da rede). */
  retry: boolean
}

export interface DescribeOptions<F extends string> {
  /** Do nome do campo na API para o campo da tela (`initialBalanceCents` -> `balance`). Campo que não está aqui vira o aviso geral. */
  fieldMap?: Record<string, F>
  /** Onde mostrar o `409` (nome em uso): este campo, com a mensagem. Sem ele, a mensagem vai ao aviso geral. */
  conflictField?: F
  /** A mensagem do `409`; pode depender do `detail` da API (por exemplo, o restaurar de categoria). */
  conflictMessage?: string | ((detail: string | undefined) => string)
  /** Quando o `409` tem causas diferentes (nome em uso, categoria principal arquivada), decide pelo detalhe da API o campo e a mensagem. Tem prioridade sobre os dois de cima. */
  conflict?: (detail: string | undefined) => { field?: F; message: string }
}

export function describeFailure<F extends string>(error: unknown, options: DescribeOptions<F> = {}): Failure<F> {
  if (!(error instanceof ApiError) || error.status >= 500) {
    return { kind: 'server', fields: {}, general: FAILURE_MESSAGES.server, retry: true }
  }
  switch (error.status) {
    case 400:
      return describeValidation(error, options)
    case 401:
      return { kind: 'session', fields: {}, general: FAILURE_MESSAGES.session, retry: false }
    case 403:
      return { kind: 'forbidden', fields: {}, general: FAILURE_MESSAGES.forbidden, retry: false }
    case 404:
      return { kind: 'notFound', fields: {}, general: FAILURE_MESSAGES.notFound, retry: false }
    case 409:
      return describeConflict(error, options)
    default:
      // Outro 4xx sem regra própria (a API recusou por uma regra de negócio): mostra o que ela disse, e só sem isso o texto genérico.
      return { kind: 'other', fields: {}, general: error.detail ?? FAILURE_MESSAGES.other, retry: false }
  }
}

function describeValidation<F extends string>(error: ApiError, options: DescribeOptions<F>): Failure<F> {
  const fields: Partial<Record<F, string>> = {}
  const others: string[] = []
  for (const item of error.errors) {
    const field = options.fieldMap?.[item.field]
    if (field === undefined) others.push(item.message)
    else fields[field] ??= item.message // o primeiro erro de cada campo vence
  }
  const general = others.length > 0 ? others.join(' ') : Object.keys(fields).length === 0 ? FAILURE_MESSAGES.invalid : undefined
  return { kind: 'validation', fields, general, retry: false }
}

function describeConflict<F extends string>(error: ApiError, options: DescribeOptions<F>): Failure<F> {
  const { conflictField, conflictMessage, conflict } = options
  if (conflict) {
    const { field, message } = conflict(error.detail)
    return field !== undefined ? { kind: 'conflict', fields: { [field]: message } as Partial<Record<F, string>>, retry: false } : { kind: 'conflict', fields: {}, general: message, retry: false }
  }
  const message = typeof conflictMessage === 'function' ? conflictMessage(error.detail) : (conflictMessage ?? error.detail ?? FAILURE_MESSAGES.other)
  if (conflictField !== undefined) {
    return { kind: 'conflict', fields: { [conflictField]: message } as Partial<Record<F, string>>, retry: false }
  }
  return { kind: 'conflict', fields: {}, general: message, retry: false }
}
