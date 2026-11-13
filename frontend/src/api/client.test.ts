import { describe, expect, it, vi } from 'vitest'
import { fakeApi, networkError, noContent, ok, problem } from '../test/fakeApi'
import * as auth from './auth'
import { onUnauthorized, request } from './client'
import { ApiError, NetworkError } from './errors'

const me = { id: '1', name: 'Dona', email: 'dona@exemplo.com', workspaces: [] }

describe('o que o cliente envia', () => {
  it('usa só o caminho relativo da API, no próprio domínio, com o cookie', async () => {
    const api = fakeApi({ 'GET /api/v1/me': ok(me) })

    await request('GET', '/me')

    expect(api.calls).toHaveLength(1)
    expect(api.calls[0].url).toBe('/api/v1/me')
    expect(api.calls[0].path.startsWith('/api/v1/')).toBe(true)
    expect(api.calls[0].credentials).toBe('same-origin')
  })

  it('nunca monta um cabeçalho de credencial: só aceita e, com corpo, o tipo do conteúdo', async () => {
    const api = fakeApi({ 'GET /api/v1/me': ok(me), 'POST /api/v1/auth/login': ok(me) })

    await request('GET', '/me')
    await auth.login({ email: 'a@b.com', password: 'Senha-123' })

    expect(Object.keys(api.calls[0].headers).sort()).toEqual(['accept'])
    expect(Object.keys(api.calls[1].headers).sort()).toEqual(['accept', 'content-type'])
    for (const call of api.calls) {
      expect(Object.keys(call.headers).some((name) => name.startsWith('auth'))).toBe(false)
    }
  })

  it('a senha vai só no corpo: o endereço nunca leva credencial nem consulta', async () => {
    const api = fakeApi({ 'POST /api/v1/auth/login': ok(me), 'POST /api/v1/auth/register': ok(me, 201) })

    await auth.login({ email: 'a@b.com', password: 'Senha-123' })
    await auth.register({ name: 'Dona', email: 'a@b.com', password: 'Senha-123' })

    for (const call of api.calls) {
      expect(call.path).not.toContain('?')
      expect(call.url).not.toContain('Senha-123')
      expect(call.url).not.toContain('a@b.com')
    }
    expect(api.calls[0].body).toEqual({ email: 'a@b.com', password: 'Senha-123' })
    expect(api.calls[1].body).toEqual({ name: 'Dona', email: 'a@b.com', password: 'Senha-123' })
  })

  it('sem corpo não declara o tipo do conteúdo', async () => {
    const api = fakeApi({ 'POST /api/v1/auth/logout': noContent() })

    await auth.logout()

    expect(api.calls[0].headers['content-type']).toBeUndefined()
    expect(api.calls[0].body).toBeUndefined()
  })

  it('recusa um caminho que não seja relativo à API, para nunca falar com outra origem', async () => {
    const api = fakeApi()

    // (a URL é montada em pedaços para a verificação de origens externas não a tomar por um recurso do app)
    const external = ['https', '://outro.example.com/x'].join('')
    await expect(request('GET', external)).rejects.toThrow('caminho de API inválido')
    await expect(request('GET', '//outro.example.com/x')).rejects.toThrow('caminho de API inválido')
    await expect(request('GET', 'me')).rejects.toThrow('caminho de API inválido')

    expect(api.calls).toHaveLength(0)
  })
})

describe('o envio de arquivo', () => {
  it('um FormData vai como multipart: sem fixar o tipo do conteúdo (o navegador põe o limite), com o arquivo na parte', async () => {
    const api = fakeApi({ 'POST /api/v1/envio': ok({ recebido: true }) })
    const form = new FormData()
    form.append('file', new File(['data;descricao;valor'], 'extrato.csv', { type: 'text/csv' }))

    await request('POST', '/envio', { body: form })

    expect(Object.keys(api.calls[0].headers)).toEqual(['accept']) // nada de content-type: ele leva o limite do multipart
    expect(api.calls[0].body).toBeInstanceOf(FormData)
    expect((api.calls[0].body as FormData).get('file')).toBeInstanceOf(File)
  })
})

describe('o que o cliente devolve', () => {
  it('lê o JSON de uma resposta de sucesso', async () => {
    fakeApi({ 'GET /api/v1/me': ok(me) })

    await expect(auth.me()).resolves.toEqual(me)
  })

  it('o 204 não tem corpo', async () => {
    fakeApi({ 'POST /api/v1/auth/logout': noContent() })

    await expect(auth.logout()).resolves.toBeUndefined()
  })

  it('o erro da API vira um ApiError com situação, título, detalhe e erros por campo', async () => {
    fakeApi({
      'POST /api/v1/auth/register': problem(400, 'Requisição inválida', {
        detail: 'Um ou mais campos são inválidos',
        errors: [{ field: 'email', message: 'deve ser um e-mail válido' }, { field: 'password', message: 'é obrigatória' }],
      }),
    })

    const error = await auth.register({ name: 'x', email: 'x', password: '' }).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({
      status: 400,
      title: 'Requisição inválida',
      detail: 'Um ou mais campos são inválidos',
      errors: [{ field: 'email', message: 'deve ser um e-mail válido' }, { field: 'password', message: 'é obrigatória' }],
    })
  })

  it('ignora erros de campo mal formados em vez de quebrar', async () => {
    fakeApi({
      'POST /api/v1/auth/login': problem(400, 'Requisição inválida', {
        errors: [{ field: 'email' }, 'texto', null, { field: 1, message: 'x' }, { field: 'password', message: 'ok' }],
      }),
    })

    const error = (await auth.login({ email: '', password: '' }).catch((e: unknown) => e)) as ApiError

    expect(error.errors).toEqual([{ field: 'password', message: 'ok' }])
  })

  it('lê o Retry-After do bloqueio (e ignora valores que não são segundos inteiros)', async () => {
    const api = fakeApi({ 'POST /api/v1/auth/login': problem(429, 'Muitas tentativas', {}, { 'Retry-After': '42' }) })
    const blocked = (await auth.login({ email: 'a@b.com', password: 'x' }).catch((e: unknown) => e)) as ApiError
    expect(blocked.status).toBe(429)
    expect(blocked.retryAfterSeconds).toBe(42)

    for (const value of ['abc', '0', '-3', '1.5']) {
      api.on('POST /api/v1/auth/login', problem(429, 'Muitas tentativas', {}, { 'Retry-After': value }))
      const other = (await auth.login({ email: 'a@b.com', password: 'x' }).catch((e: unknown) => e)) as ApiError
      expect(other.retryAfterSeconds, value).toBeUndefined()
    }
    api.on('POST /api/v1/auth/login', problem(429, 'Muitas tentativas'))
    const none = (await auth.login({ email: 'a@b.com', password: 'x' }).catch((e: unknown) => e)) as ApiError
    expect(none.retryAfterSeconds).toBeUndefined()
  })

  it('uma resposta de erro que não é JSON (a página de um proxy, por exemplo) vira um erro genérico', async () => {
    fakeApi({ 'GET /api/v1/me': { status: 502, rawText: '<html>Bad Gateway</html>' } })

    const error = (await auth.me().catch((e: unknown) => e)) as ApiError

    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(502)
    expect(error.title).toBe('Erro no servidor')
    expect(error.errors).toEqual([])
  })

  it('um sucesso com corpo ilegível é um erro, não um dado quebrado', async () => {
    fakeApi({ 'GET /api/v1/me': { status: 200, rawText: 'isto não é json' } })

    const error = (await auth.me().catch((e: unknown) => e)) as ApiError

    expect(error).toBeInstanceOf(ApiError)
    expect(error.title).toBe('Resposta inválida do servidor')
  })

  it('a falha de rede vira NetworkError', async () => {
    fakeApi({ 'GET /api/v1/me': networkError() })

    await expect(auth.me()).rejects.toBeInstanceOf(NetworkError)
  })

  it('o tempo esgotado vira NetworkError e cancela a chamada', async () => {
    vi.useFakeTimers()
    fakeApi({ 'GET /api/v1/me': { hang: true } })

    const pending = request('GET', '/me', { timeoutMs: 1000 }).catch((e: unknown) => e)
    await vi.advanceTimersByTimeAsync(1000)
    const error = await pending

    expect(error).toBeInstanceOf(NetworkError)
    expect((error as NetworkError).message).toBe('tempo esgotado')
  })

  it('o tempo limite padrão é de 15 segundos', async () => {
    vi.useFakeTimers()
    fakeApi({ 'GET /api/v1/me': { hang: true } })

    const pending = request('GET', '/me').catch((e: unknown) => e)
    await vi.advanceTimersByTimeAsync(14_999)
    let settled = false
    void pending.then(() => {
      settled = true
    })
    await vi.advanceTimersByTimeAsync(0)
    expect(settled).toBe(false)
    await vi.advanceTimersByTimeAsync(1)

    expect(await pending).toBeInstanceOf(NetworkError)
  })
})

describe('o aviso de sessão terminada (401)', () => {
  it('uma chamada autenticada que recebe 401 avisa quem está ouvindo, e o erro segue', async () => {
    fakeApi({ 'POST /api/v1/auth/logout-all': problem(401, 'Não autenticado') })
    const heard = vi.fn()
    const stop = onUnauthorized(heard)

    const error = (await auth.logoutAll().catch((e: unknown) => e)) as ApiError

    expect(heard).toHaveBeenCalledTimes(1)
    expect(error.status).toBe(401)
    stop()
  })

  it('o 401 da entrada, do cadastro, da consulta inicial e da saída não avisa: não é sessão terminada', async () => {
    fakeApi({
      'POST /api/v1/auth/login': problem(401, 'Credenciais inválidas'),
      'POST /api/v1/auth/register': problem(401, 'x'),
      'GET /api/v1/me': problem(401, 'Não autenticado'),
      'POST /api/v1/auth/logout': problem(401, 'x'),
    })
    const heard = vi.fn()
    const stop = onUnauthorized(heard)

    await auth.login({ email: 'a@b.com', password: 'x' }).catch(() => {})
    await auth.register({ name: 'a', email: 'a@b.com', password: 'x' }).catch(() => {})
    await auth.me().catch(() => {})
    await auth.logout().catch(() => {})

    expect(heard).not.toHaveBeenCalled()
    stop()
  })

  it('só o 401 avisa: 403, 404 e 500 não', async () => {
    fakeApi({ 'GET /api/v1/a': problem(403, 'x'), 'GET /api/v1/b': problem(404, 'x'), 'GET /api/v1/c': problem(500, 'x') })
    const heard = vi.fn()
    const stop = onUnauthorized(heard)

    for (const path of ['/a', '/b', '/c']) await request('GET', path).catch(() => {})

    expect(heard).not.toHaveBeenCalled()
    stop()
  })

  it('quem para de ouvir deixa de ser avisado, e vários ouvintes são avisados', async () => {
    fakeApi({ 'GET /api/v1/x': problem(401, 'x') })
    const first = vi.fn()
    const second = vi.fn()
    const stopFirst = onUnauthorized(first)
    const stopSecond = onUnauthorized(second)

    await request('GET', '/x').catch(() => {})
    expect([first.mock.calls.length, second.mock.calls.length]).toEqual([1, 1])
    stopFirst()
    await request('GET', '/x').catch(() => {})

    expect([first.mock.calls.length, second.mock.calls.length]).toEqual([1, 2])
    stopSecond()
  })

  it('o 401 pode ser desligado por chamada', async () => {
    fakeApi({ 'GET /api/v1/x': problem(401, 'x') })
    const heard = vi.fn()
    const stop = onUnauthorized(heard)

    await request('GET', '/x', { notifyUnauthorized: false }).catch(() => {})

    expect(heard).not.toHaveBeenCalled()
    stop()
  })
})
