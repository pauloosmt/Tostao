// O único ponto por onde o aplicativo fala com a API. Regras da constituição que vivem aqui:
//  * só o caminho /api/v1 do próprio domínio (o navegador nunca fala com outra origem);
//  * a sessão é o cookie HttpOnly, enviado pelo navegador: o aplicativo não lê nem monta credencial nenhuma;
//  * nenhum token, senha ou código vai no endereço: o corpo é JSON.
import { ApiError, NetworkError } from './errors'
import type { FieldError, Problem } from './types'

const BASE = '/api/v1'
export const REQUEST_TIMEOUT_MS = 15_000

type UnauthorizedListener = () => void
const listeners = new Set<UnauthorizedListener>()

/** Avisa quando uma chamada autenticada recebe "sem sessão" (401). Devolve a função que cancela o aviso. */
export function onUnauthorized(listener: UnauthorizedListener): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export interface RequestOptions {
  body?: unknown
  /**
   * Um 401 desta chamada encerra a sessão no aplicativo (padrão). Desligue nas chamadas em que o 401 é uma resposta normal:
   * a entrada e o cadastro (credencial inválida) e a consulta inicial de "quem sou eu" (ainda não havia sessão).
   */
  notifyUnauthorized?: boolean
  timeoutMs?: number
}

function parseFieldErrors(value: unknown): FieldError[] {
  if (!Array.isArray(value)) return []
  return value.filter(
    (item): item is FieldError =>
      typeof item === 'object' && item !== null && typeof (item as FieldError).field === 'string' && typeof (item as FieldError).message === 'string',
  )
}

function parseRetryAfter(header: string | null): number | undefined {
  if (!header) return undefined
  const seconds = Number(header)
  return Number.isInteger(seconds) && seconds > 0 ? seconds : undefined
}

export async function request<T = void>(method: string, path: string, options: RequestOptions = {}): Promise<T> {
  if (!path.startsWith('/') || path.startsWith('//')) {
    throw new Error(`caminho de API inválido: ${path} (use um caminho relativo que comece com /)`)
  }
  const { body, notifyUnauthorized = true, timeoutMs = REQUEST_TIMEOUT_MS } = options
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)

  let response: Response
  let text: string
  try {
    response = await fetch(BASE + path, {
      method,
      credentials: 'same-origin',
      signal: controller.signal,
      headers: {
        Accept: 'application/json, application/problem+json',
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    })
    text = response.status === 204 ? '' : await response.text()
  }
  catch (cause) {
    // o fetch rejeita (sem rede, servidor fora do ar) ou o tempo esgotou no meio da resposta
    throw new NetworkError(controller.signal.aborted ? 'tempo esgotado' : 'sem conexão com o servidor', { cause })
  }
  finally {
    clearTimeout(timer)
  }

  let payload: unknown
  let unreadable = false
  try {
    payload = text === '' ? undefined : JSON.parse(text)
  }
  catch {
    unreadable = true
  }

  if (response.ok) {
    if (unreadable) throw new ApiError({ status: response.status, title: 'Resposta inválida do servidor' })
    return payload as T
  }

  const problem = (typeof payload === 'object' && payload !== null ? payload : {}) as Problem
  if (response.status === 401 && notifyUnauthorized) listeners.forEach((listener) => listener())
  throw new ApiError({
    status: response.status,
    title: typeof problem.title === 'string' && problem.title !== '' ? problem.title : 'Erro no servidor',
    detail: typeof problem.detail === 'string' ? problem.detail : undefined,
    errors: parseFieldErrors(problem.errors),
    retryAfterSeconds: parseRetryAfter(response.headers.get('Retry-After')),
  })
}
