// A API de mentira dos testes: troca o `fetch` global por uma tabela de rotas e registra tudo o que o aplicativo pediu
// (método, caminho, cabeçalhos e corpo). Uma chamada sem rota é um erro do teste, nunca um silêncio.
import { vi } from 'vitest'

export interface Call {
  method: string
  /** Só o caminho (sem a origem), com a consulta. */
  path: string
  url: string
  headers: Record<string, string>
  body: unknown
  credentials?: RequestCredentials
}

export interface Reply {
  status?: number
  body?: unknown
  headers?: Record<string, string>
  /** O corpo vai como application/problem+json. */
  problem?: boolean
  /** Falha de rede (o fetch rejeita). */
  networkError?: boolean
  /** Nunca responde: só termina se a chamada for abortada (para provar o tempo limite). */
  hang?: boolean
  /** Corpo que não é JSON. */
  rawText?: string
}

export type Handler = Reply |((call: Call) => Reply | Promise<Reply>)

export interface FakeApi {
  calls: Call[]
  on: (route: string, handler: Handler) => FakeApi
  /** Quantas vezes a rota foi chamada. */
  count: (route: string) => number
  callsTo: (route: string) => Call[]
}

// As chamadas sem rota. O afterEach do setup falha o teste que deixar alguma, para um erro engolido pelo aplicativo
// (um catch que vira NetworkError, por exemplo) nunca esconder uma rota que o teste esqueceu de declarar.
const unhandled: string[] = []

export function takeUnhandledCalls(): string[] {
  return unhandled.splice(0, unhandled.length)
}

export const ok = (body?: unknown, status = 200): Reply => ({ status, body })
export const noContent = (): Reply => ({ status: 204 })
export const problem = (status: number, title: string, extra: Record<string, unknown> = {}, headers?: Record<string, string>): Reply => ({
  status,
  problem: true,
  body: { type: 'about:blank', title, status, ...extra },
  headers,
})
export const networkError = (): Reply => ({ networkError: true })

function headersToObject(init: HeadersInit | undefined): Record<string, string> {
  const out: Record<string, string> = {}
  if (!init) return out
  new Headers(init).forEach((value, key) => {
    out[key.toLowerCase()] = value
  })
  return out
}

/** Instala o `fetch` falso. Chame uma vez por teste; o `afterEach` do setup desfaz. */
export function fakeApi(routes: Record<string, Handler> = {}): FakeApi {
  const table = new Map<string, Handler>(Object.entries(routes))
  const calls: Call[] = []

  const api: FakeApi = {
    calls,
    on(route, handler) {
      table.set(route, handler)
      return api
    },
    count: (route) => api.callsTo(route).length,
    callsTo: (route) => calls.filter((call) => `${call.method} ${call.path.split('?')[0]}` === route),
  }

  vi.stubGlobal('fetch', async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
    const method = (init.method ?? 'GET').toUpperCase()
    const path = url.startsWith('http') ? new URL(url).pathname + new URL(url).search : url
    const call: Call = {
      method,
      path,
      url,
      headers: headersToObject(init.headers),
      body: typeof init.body === 'string' ? JSON.parse(init.body) : init.body,
      credentials: init.credentials,
    }
    calls.push(call)

    const handler = table.get(`${method} ${path.split('?')[0]}`)
    if (!handler) {
      unhandled.push(`${method} ${path}`)
      throw new Error(`fakeApi: nenhuma rota para ${method} ${path}`)
    }
    const reply = typeof handler === 'function' ? await handler(call) : handler

    if (reply.networkError) throw new TypeError('Failed to fetch')
    if (reply.hang) {
      await new Promise((_resolve, reject) => {
        init.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
      })
    }
    const status = reply.status ?? 200
    const headers = new Headers(reply.headers)
    if (reply.rawText !== undefined) {
      headers.set('Content-Type', 'text/html')
      return new Response(reply.rawText, { status, headers })
    }
    if (status === 204 || reply.body === undefined) return new Response(null, { status, headers })
    headers.set('Content-Type', reply.problem ? 'application/problem+json' : 'application/json')
    return new Response(JSON.stringify(reply.body), { status, headers })
  })

  return api
}
