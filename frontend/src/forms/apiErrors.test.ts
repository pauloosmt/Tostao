import { describe, expect, it } from 'vitest'
import { ApiError, NetworkError } from '../api/errors'
import { FAILURE_MESSAGES, describeFailure } from './apiErrors'

type Field = 'name' | 'balance' | 'limit'
const fieldMap = { name: 'name', initialBalanceCents: 'balance', creditLimitCents: 'limit' } as const
const api = (status: number, extra: Partial<ConstructorParameters<typeof ApiError>[0]> = {}) => new ApiError({ status, title: 'x', ...extra })

describe('400: erros de campo', () => {
  it('cada erro vai ao campo da tela, pelo mapa de nomes da API', () => {
    const failure = describeFailure<Field>(
      api(400, {
        errors: [
          { field: 'initialBalanceCents', message: 'fora do limite' },
          { field: 'name', message: 'é obrigatório' },
        ],
      }),
      { fieldMap },
    )

    expect(failure).toEqual({ kind: 'validation', fields: { balance: 'fora do limite', name: 'é obrigatório' }, general: undefined, retry: false })
  })

  it('o primeiro erro de cada campo vence', () => {
    const failure = describeFailure<Field>(
      api(400, {
        errors: [
          { field: 'name', message: 'primeiro' },
          { field: 'name', message: 'segundo' },
        ],
      }),
      { fieldMap },
    )

    expect(failure.fields).toEqual({ name: 'primeiro' })
  })

  it('o campo que a tela não tem vira o aviso geral, junto dos que têm', () => {
    const failure = describeFailure<Field>(
      api(400, {
        errors: [
          { field: 'name', message: 'é obrigatório' },
          { field: 'type', message: 'tipo inválido' },
          { field: 'outro', message: 'campo estranho' },
        ],
      }),
      { fieldMap },
    )

    expect(failure.fields).toEqual({ name: 'é obrigatório' })
    expect(failure.general).toBe('tipo inválido campo estranho')
  })

  it('sem erros por campo, o aviso geral pede para conferir os dados', () => {
    expect(describeFailure<Field>(api(400), { fieldMap }).general).toBe(FAILURE_MESSAGES.invalid)
    expect(describeFailure<Field>(api(400, { errors: [] }), { fieldMap }).fields).toEqual({})
  })

  it('sem mapa de campos, tudo vai ao aviso geral', () => {
    const failure = describeFailure(api(400, { errors: [{ field: 'name', message: 'é obrigatório' }] }))

    expect(failure.fields).toEqual({})
    expect(failure.general).toBe('é obrigatório')
  })
})

describe('409: conflito', () => {
  it('com campo de conflito, a mensagem vai ao campo (nome em uso) e não ao aviso geral', () => {
    const failure = describeFailure<Field>(api(409), { conflictField: 'name', conflictMessage: 'Já existe uma conta ativa com este nome.' })

    expect(failure).toEqual({ kind: 'conflict', fields: { name: 'Já existe uma conta ativa com este nome.' }, retry: false })
  })

  it('sem campo, a mensagem vai ao aviso geral; a mensagem pode depender do detalhe da API', () => {
    const message = (detail?: string) => (detail?.includes('principal') ? 'Restaure antes a categoria principal.' : 'Já existe uma categoria ativa com este nome aqui.')

    expect(describeFailure(api(409, { detail: 'categoria principal arquivada' }), { conflictMessage: message }).general).toBe('Restaure antes a categoria principal.')
    expect(describeFailure(api(409, { detail: 'nome em uso' }), { conflictMessage: message }).general).toBe('Já existe uma categoria ativa com este nome aqui.')
  })

  it('sem mensagem definida, usa o detalhe da API e, sem ele, o aviso genérico', () => {
    expect(describeFailure(api(409, { detail: 'conflito do servidor' })).general).toBe('conflito do servidor')
    expect(describeFailure(new ApiError({ status: 409, title: '' })).general).toBe(FAILURE_MESSAGES.other)
  })
})

describe('os outros erros', () => {
  it('403: sem permissão', () => {
    expect(describeFailure(api(403))).toEqual({ kind: 'forbidden', fields: {}, general: FAILURE_MESSAGES.forbidden, retry: false })
  })

  it('404: não encontrada', () => {
    expect(describeFailure(api(404))).toEqual({ kind: 'notFound', fields: {}, general: FAILURE_MESSAGES.notFound, retry: false })
  })

  it('401: sessão terminada (a navegação para a entrada é do cliente)', () => {
    expect(describeFailure(api(401)).kind).toBe('session')
  })

  it('5xx, falha de rede e erro desconhecido: servidor, com nova tentativa', () => {
    for (const error of [api(500), api(503), new NetworkError('sem conexão com o servidor'), new Error('qualquer coisa'), 'texto', undefined]) {
      expect(describeFailure(error)).toEqual({ kind: 'server', fields: {}, general: FAILURE_MESSAGES.server, retry: true })
    }
  })

  it('outro 4xx: aviso genérico, sem nova tentativa', () => {
    expect(describeFailure(api(422))).toEqual({ kind: 'other', fields: {}, general: FAILURE_MESSAGES.other, retry: false })
    expect(describeFailure(api(429)).retry).toBe(false)
  })

  it('só o servidor e a rede oferecem "Tentar de novo"', () => {
    const retries = [400, 401, 403, 404, 409, 422, 500, 502].filter((status) => describeFailure(api(status)).retry)

    expect(retries).toEqual([500, 502])
  })
})
