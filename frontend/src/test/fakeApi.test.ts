import { describe, expect, it } from 'vitest'
import { fakeApi, networkError, noContent, ok, problem, takeUnhandledCalls } from './fakeApi'

describe('fakeApi', () => {
  it('responde pela tabela de rotas e registra o que foi pedido', async () => {
    const api = fakeApi({ 'GET /api/v1/me': ok({ id: '1' }) })

    const response = await fetch('/api/v1/me?x=1', { credentials: 'same-origin', headers: { Accept: 'application/json' } })

    expect(response.status).toBe(200)
    expect(await response.json()).toEqual({ id: '1' })
    expect(api.calls).toHaveLength(1)
    expect(api.calls[0]).toMatchObject({ method: 'GET', path: '/api/v1/me?x=1', credentials: 'same-origin' })
    expect(api.calls[0].headers.accept).toBe('application/json')
    expect(api.count('GET /api/v1/me')).toBe(1)
  })

  it('lê o corpo JSON enviado, responde 204 sem corpo e problem+json', async () => {
    const api = fakeApi({
      'POST /api/v1/auth/login': (call) => (call.body && (call.body as { password: string }).password === 'certa' ? noContent() : problem(401, 'Credenciais inválidas')),
    })

    const bad = await fetch('/api/v1/auth/login', { method: 'POST', body: JSON.stringify({ password: 'errada' }) })
    const good = await fetch('/api/v1/auth/login', { method: 'POST', body: JSON.stringify({ password: 'certa' }) })

    expect(bad.status).toBe(401)
    expect(bad.headers.get('content-type')).toBe('application/problem+json')
    expect(await bad.json()).toMatchObject({ title: 'Credenciais inválidas', status: 401 })
    expect(good.status).toBe(204)
    expect(await good.text()).toBe('')
    expect(api.calls.map((call) => call.body)).toEqual([{ password: 'errada' }, { password: 'certa' }])
  })

  it('simula falha de rede e chamada que só termina ao ser abortada', async () => {
    fakeApi({ 'GET /a': networkError(), 'GET /b': { hang: true } })

    await expect(fetch('/a')).rejects.toThrow('Failed to fetch')
    const controller = new AbortController()
    const pending = fetch('/b', { signal: controller.signal })
    controller.abort()
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' })
  })

  it('uma chamada sem rota é um erro do teste', async () => {
    fakeApi()

    await expect(fetch('/api/v1/outra')).rejects.toThrow('nenhuma rota para GET /api/v1/outra')

    // o setup falharia o teste por isso; aqui é o esperado
    expect(takeUnhandledCalls()).toEqual(['GET /api/v1/outra'])
  })
})

describe('fakeApi: rotas com identificador (onMatch)', () => {
  it('casa pelo caminho sem a consulta e entrega o que o padrão capturou', async () => {
    const api = fakeApi()
    api.onMatch('GET', /^\/api\/v1\/itens\/([^/]+)$/, (_call, match) => ok({ id: match[1] }))

    const response = await fetch('/api/v1/itens/abc?x=1')

    expect(await response.json()).toEqual({ id: 'abc' })
    expect(api.calls).toHaveLength(1)
  })

  it('uma rota exata vale antes de qualquer padrão, e o método precisa casar', async () => {
    const api = fakeApi({ 'GET /api/v1/itens/especial': ok({ id: 'exata' }) })
    api.onMatch('GET', /^\/api\/v1\/itens\/([^/]+)$/, (_call, match) => ok({ id: match[1] }))
    api.onMatch('DELETE', /^\/api\/v1\/itens\/([^/]+)$/, () => noContent())

    expect(await (await fetch('/api/v1/itens/especial')).json()).toEqual({ id: 'exata' })
    expect((await fetch('/api/v1/itens/x', { method: 'DELETE' })).status).toBe(204)
  })

  it('entre dois padrões que casam, o registrado por último vale (o teste pode trocar a resposta)', async () => {
    const api = fakeApi()
    api.onMatch('GET', /^\/api\/v1\/itens\/([^/]+)$/, () => ok({ de: 'primeiro' }))
    api.onMatch('GET', /^\/api\/v1\/itens\/([^/]+)$/, () => ok({ de: 'segundo' }))

    expect(await (await fetch('/api/v1/itens/x')).json()).toEqual({ de: 'segundo' })
  })

  it('sem rota nem padrão, é uma chamada sem rota (o teste falha, não passa em silêncio)', async () => {
    fakeApi().onMatch('GET', /^\/api\/v1\/itens\/x$/, () => ok({}))

    await expect(fetch('/api/v1/outra')).rejects.toThrow('nenhuma rota')
    expect(takeUnhandledCalls()).toEqual(['GET /api/v1/outra'])
  })
})
