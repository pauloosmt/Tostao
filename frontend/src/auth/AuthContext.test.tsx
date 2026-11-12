import { act, screen, waitFor } from '@testing-library/react'
import { useEffect } from 'react'
import { describe, expect, it } from 'vitest'
import { request } from '../api/client'
import { ApiError, NetworkError } from '../api/errors'
import type { Me } from '../api/types'
import { fakeApi, networkError, noContent, ok, problem, type Reply } from '../test/fakeApi'
import { renderWithUser } from '../test/render'
import { AuthProvider } from './AuthContext'
import { useAuth, type AuthValue } from './useAuth'

const me: Me = {
  id: '11111111-1111-1111-1111-111111111111',
  name: 'Dona Ana',
  email: 'ana@exemplo.com',
  workspaces: [{ id: 'w1', name: 'Casa', baseCurrency: 'BRL', role: 'OWNER' }],
}

// Mostra o estado e deixa o teste chamar as ações exatamente como as telas chamam.
const latest: { value: AuthValue | null } = { value: null }
const auth = (): AuthValue => {
  if (latest.value === null) throw new Error('o Probe ainda não renderizou')
  return latest.value
}
function Probe() {
  const current = useAuth()
  useEffect(() => {
    latest.value = current
  })
  const { state, retry } = current
  return (
    <div>
      <p data-testid="status">{state.status}</p>
      {state.status === 'authenticated' && <p>Olá, {state.me.name}</p>}
      {state.status === 'anonymous' && <p data-testid="expired">{String(state.expired)}</p>}
      <button type="button" onClick={retry}>
        Tentar de novo
      </button>
    </div>
  )
}

function mount() {
  return renderWithUser(
    <AuthProvider>
      <Probe />
    </AuthProvider>,
  )
}

const status = () => screen.getByTestId('status').textContent
const settled = (expected: string) => waitFor(() => expect(status()).toBe(expected))

/** Uma resposta que o teste libera quando quiser (para ver o estado enquanto a chamada está em andamento). */
function gate(reply: Reply) {
  let release!: () => void
  const wait = new Promise<void>((resolve) => {
    release = resolve
  })
  return { handler: async () => (await wait, reply), release }
}

describe('ao abrir', () => {
  it('mostra "carregando" e depois restaura a sessão pelo /me', async () => {
    const pending = gate(ok(me))
    const api = fakeApi({ 'GET /api/v1/me': pending.handler })

    mount()
    expect(status()).toBe('loading')
    pending.release()

    await settled('authenticated')
    expect(screen.getByText('Olá, Dona Ana')).toBeInTheDocument()
    expect(api.count('GET /api/v1/me')).toBe(1)
  })

  it('o 401 é "ainda sem sessão": anônimo, sem o aviso de sessão terminada', async () => {
    fakeApi({ 'GET /api/v1/me': problem(401, 'Não autenticado') })

    mount()

    await settled('anonymous')
    expect(screen.getByTestId('expired')).toHaveTextContent('false')
  })

  it('servidor fora do ar (rede ou 5xx) é "indisponível", e "Tentar de novo" consulta outra vez', async () => {
    const api = fakeApi({ 'GET /api/v1/me': networkError() })
    const { user } = mount()
    await settled('unavailable')

    api.on('GET /api/v1/me', problem(503, 'Indisponível'))
    await user.click(screen.getByRole('button', { name: 'Tentar de novo' }))
    await settled('unavailable')

    api.on('GET /api/v1/me', ok(me))
    await user.click(screen.getByRole('button', { name: 'Tentar de novo' }))
    await settled('authenticated')
    expect(api.count('GET /api/v1/me')).toBe(3)
  })

  it('a nova tentativa mostra "carregando" enquanto consulta', async () => {
    const api = fakeApi({ 'GET /api/v1/me': networkError() })
    const { user } = mount()
    await settled('unavailable')

    const pending = gate(ok(me))
    api.on('GET /api/v1/me', pending.handler)
    await user.click(screen.getByRole('button', { name: 'Tentar de novo' }))
    expect(status()).toBe('loading')
    pending.release()

    await settled('authenticated')
  })
})

describe('entrar e cadastrar', () => {
  it('a entrada com sucesso leva à sessão e envia só o e-mail e a senha, no corpo', async () => {
    const api = fakeApi({ 'GET /api/v1/me': problem(401, 'Não autenticado'), 'POST /api/v1/auth/login': ok(me) })
    mount()
    await settled('anonymous')

    await act(() => auth().login({ email: 'ana@exemplo.com', password: 'Senha-Forte-123' }))

    expect(status()).toBe('authenticated')
    expect(api.callsTo('POST /api/v1/auth/login')[0].body).toEqual({ email: 'ana@exemplo.com', password: 'Senha-Forte-123' })
  })

  it('o cadastro com sucesso também leva à sessão', async () => {
    fakeApi({ 'GET /api/v1/me': problem(401, 'Não autenticado'), 'POST /api/v1/auth/register': ok(me, 201) })
    mount()
    await settled('anonymous')

    await act(() => auth().register({ name: 'Dona Ana', email: 'ana@exemplo.com', password: 'Senha-Forte-123' }))

    expect(status()).toBe('authenticated')
    expect(screen.getByText('Olá, Dona Ana')).toBeInTheDocument()
  })

  it('a credencial recusada falha com o erro da API e a sessão continua anônima, sem aviso de sessão terminada', async () => {
    fakeApi({ 'GET /api/v1/me': problem(401, 'Não autenticado'), 'POST /api/v1/auth/login': problem(401, 'Credenciais inválidas') })
    mount()
    await settled('anonymous')

    const error = await act(() => auth().login({ email: 'ana@exemplo.com', password: 'errada' }).catch((e: unknown) => e))

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).status).toBe(401)
    expect(status()).toBe('anonymous')
    expect(screen.getByTestId('expired')).toHaveTextContent('false')
  })

  it('a falha de rede na entrada segue como NetworkError, sem mudar a sessão', async () => {
    fakeApi({ 'GET /api/v1/me': problem(401, 'Não autenticado'), 'POST /api/v1/auth/login': networkError() })
    mount()
    await settled('anonymous')

    const error = await act(() => auth().login({ email: 'ana@exemplo.com', password: 'x' }).catch((e: unknown) => e))

    expect(error).toBeInstanceOf(NetworkError)
    expect(status()).toBe('anonymous')
  })

  it('uma consulta inicial atrasada não desfaz a entrada', async () => {
    const slow = gate(problem(401, 'Não autenticado'))
    fakeApi({ 'GET /api/v1/me': slow.handler, 'POST /api/v1/auth/login': ok(me) })
    mount()

    await act(() => auth().login({ email: 'ana@exemplo.com', password: 'Senha-Forte-123' }))
    expect(status()).toBe('authenticated')
    await act(async () => slow.release())

    expect(status()).toBe('authenticated')
  })

  it('a senha nunca fica no estado, nem a resposta da entrada guarda token algum', async () => {
    fakeApi({ 'GET /api/v1/me': problem(401, 'Não autenticado'), 'POST /api/v1/auth/login': ok(me) })
    mount()
    await settled('anonymous')

    await act(() => auth().login({ email: 'ana@exemplo.com', password: 'Senha-Forte-123' }))

    const serialized = JSON.stringify(auth().state)
    expect(serialized).not.toContain('Senha-Forte-123')
    expect(serialized.toLowerCase()).not.toContain('password')
    expect(serialized.toLowerCase()).not.toContain('token')
    expect(Object.keys(auth().state).sort()).toEqual(['me', 'status'])
    expect(Object.keys((auth().state as { me: Me }).me).sort()).toEqual(['email', 'id', 'name', 'workspaces'])
  })
})

describe('sair', () => {
  async function signedIn() {
    const api = fakeApi({ 'GET /api/v1/me': ok(me) })
    mount()
    await settled('authenticated')
    return api
  }

  it('só limpa a sessão depois do 204: enquanto a chamada está em andamento a pessoa continua dentro', async () => {
    const api = await signedIn()
    const pending = gate(noContent())
    api.on('POST /api/v1/auth/logout', pending.handler)

    let finished: Promise<void> = Promise.resolve()
    act(() => {
      finished = auth().logout()
    })
    expect(status()).toBe('authenticated')
    await act(async () => {
      pending.release()
      await finished
    })

    expect(status()).toBe('anonymous')
    expect(screen.getByTestId('expired')).toHaveTextContent('false')
  })

  it('a falha ao sair mantém a sessão e o erro segue para a tela avisar', async () => {
    const api = await signedIn()

    api.on('POST /api/v1/auth/logout', problem(500, 'Erro'))
    const serverError = await act(() => auth().logout().catch((e: unknown) => e))
    expect(serverError).toBeInstanceOf(ApiError)
    expect(status()).toBe('authenticated')

    api.on('POST /api/v1/auth/logout', networkError())
    const offline = await act(() => auth().logout().catch((e: unknown) => e))
    expect(offline).toBeInstanceOf(NetworkError)
    expect(status()).toBe('authenticated')
  })

  it('se a sessão já não existia (401 ao sair), a pessoa fica fora: era o que se queria', async () => {
    const api = await signedIn()
    api.on('POST /api/v1/auth/logout', problem(401, 'Não autenticado'))

    await act(() => auth().logout())

    expect(status()).toBe('anonymous')
    expect(screen.getByTestId('expired')).toHaveTextContent('false')
  })

  it('sair de todos os aparelhos só limpa depois do 204, e a falha mantém a sessão', async () => {
    const api = await signedIn()

    api.on('POST /api/v1/auth/logout-all', problem(500, 'Erro'))
    const failure = await act(() => auth().logoutAll().catch((e: unknown) => e))
    expect(failure).toBeInstanceOf(ApiError)
    expect(status()).toBe('authenticated')

    api.on('POST /api/v1/auth/logout-all', noContent())
    await act(() => auth().logoutAll())
    expect(status()).toBe('anonymous')
    expect(screen.getByTestId('expired')).toHaveTextContent('false')
  })

  it('sair de todos com 401 leva à entrada com o aviso de sessão terminada, sem erro para a tela', async () => {
    const api = await signedIn()
    api.on('POST /api/v1/auth/logout-all', problem(401, 'Não autenticado'))

    await act(() => auth().logoutAll())

    expect(status()).toBe('anonymous')
    expect(screen.getByTestId('expired')).toHaveTextContent('true')
  })
})

describe('a sessão que termina', () => {
  it('um 401 em chamada autenticada depois da abertura leva à entrada com o aviso', async () => {
    const api = fakeApi({ 'GET /api/v1/me': ok(me), 'GET /api/v1/contas': problem(401, 'Não autenticado') })
    mount()
    await settled('authenticated')

    await act(() => request('GET', '/contas').catch(() => undefined))

    expect(status()).toBe('anonymous')
    expect(screen.getByTestId('expired')).toHaveTextContent('true')
    expect(api.count('GET /api/v1/contas')).toBe(1)
  })

  it('outros erros (403, 404, 500) não encerram a sessão', async () => {
    fakeApi({
      'GET /api/v1/me': ok(me),
      'GET /api/v1/a': problem(403, 'x'),
      'GET /api/v1/b': problem(404, 'x'),
      'GET /api/v1/c': problem(500, 'x'),
    })
    mount()
    await settled('authenticated')

    for (const path of ['/a', '/b', '/c']) await act(() => request('GET', path).catch(() => undefined))

    expect(status()).toBe('authenticated')
  })

})

describe('useAuth', () => {
  it('fora do AuthProvider é um erro de uso, dito com clareza', () => {
    const silence = console.error
    console.error = () => {}
    try {
      expect(() => renderWithUser(<Probe />)).toThrow('useAuth precisa estar dentro de um AuthProvider')
    } finally {
      console.error = silence
    }
  })
})

describe('refresh', () => {
  it('atualiza os workspaces e os papéis sem passar por "carregando"; se falha, a sessão fica como estava', async () => {
    const api = fakeApi({ 'GET /api/v1/me': ok(me) })
    mount()
    await settled('authenticated')
    const names = () => {
      const state = auth().state
      return state.status === 'authenticated' ? state.me.workspaces.map((workspace) => workspace.name) : []
    }

    const joined: Me = { ...me, workspaces: [...me.workspaces, { id: 'w2', name: 'Viagem', baseCurrency: 'BRL', role: 'EDITOR' }] }
    api.on('GET /api/v1/me', ok(joined))
    await act(async () => auth().refresh())

    expect(status()).toBe('authenticated')
    expect(names()).toEqual(['Casa', 'Viagem'])

    api.on('GET /api/v1/me', networkError())
    await expect(act(async () => auth().refresh())).rejects.toBeInstanceOf(NetworkError)
    expect(status()).toBe('authenticated')
    expect(names()).toHaveLength(2)
  })
})
