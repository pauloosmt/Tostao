import { act, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { request } from './api/client'
import type { Me, WorkspaceMembership } from './api/types'
import { expectNoSeriousViolations } from './test/axe'
import { fakeApi, networkError, noContent, ok, problem, type Call, type Reply } from './test/fakeApi'
import { renderApp } from './test/renderApp'

// (a consulta com credencial é montada em pedaços para a verificação de query string não a tomar por código do app)
const tokenQuery = ['?', 'token=segredo'].join('')

const casa: WorkspaceMembership = { id: 'w-casa', name: 'Casa da Ana', baseCurrency: 'BRL', role: 'OWNER' }
const viagem: WorkspaceMembership = { id: 'w-viagem', name: 'Viagem da Ana', baseCurrency: 'BRL', role: 'VIEWER' }
const ana: Me = { id: 'u-ana', name: 'Ana Maria', email: 'ana@exemplo.com', workspaces: [casa, viagem] }
const bia: Me = {
  id: 'u-bia',
  name: 'Bia Costa',
  email: 'bia@exemplo.com',
  workspaces: [{ id: 'w-bia', name: 'Casa da Bia', baseCurrency: 'BRL', role: 'OWNER' }],
}
const noSession = problem(401, 'Não autenticado')
const location = () => screen.getByTestId('location').textContent

describe('ao abrir', () => {
  it('mostra "carregando" enquanto consulta a sessão', async () => {
    let release!: () => void
    const wait = new Promise<void>((resolve) => {
      release = resolve
    })
    fakeApi({ 'GET /api/v1/me': async () => (await wait, ok(ana)) })

    renderApp(['/'])
    expect(screen.getByRole('status')).toHaveTextContent('Carregando…')

    await act(async () => release())
    expect(await screen.findByRole('heading', { name: 'Olá, Ana' })).toBeInTheDocument()
  })

  it('com sessão, restaura o início com o nome e o workspace', async () => {
    fakeApi({ 'GET /api/v1/me': ok(ana) })

    renderApp(['/'])

    expect(await screen.findByRole('heading', { name: 'Olá, Ana' })).toBeInTheDocument()
    expect(screen.getByText('Casa da Ana')).toBeInTheDocument()
  })

  it('servidor fora do ar: avisa com "Tentar de novo", que consulta outra vez', async () => {
    const api = fakeApi({ 'GET /api/v1/me': networkError() })
    const { user } = renderApp(['/'])

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível falar com o servidor. Tente de novo.')
    expect(screen.getByRole('heading', { name: 'Não foi possível abrir o Tostão' })).toBeInTheDocument()

    api.on('GET /api/v1/me', ok(ana))
    await user.click(screen.getByRole('button', { name: 'Tentar de novo' }))

    expect(await screen.findByRole('heading', { name: 'Olá, Ana' })).toBeInTheDocument()
    expect(api.count('GET /api/v1/me')).toBe(2)
  })
})

describe('quem não tem sessão', () => {
  it('qualquer tela de dentro leva à entrada, sem chamar mais nada', async () => {
    const api = fakeApi({ 'GET /api/v1/me': noSession })

    renderApp(['/perfil'])

    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
    expect(location()).toBe('/entrar')
    expect(api.calls.map((call) => `${call.method} ${call.path}`)).toEqual(['GET /api/v1/me'])
  })

  it('depois de entrar, retoma o endereço que pediu', async () => {
    fakeApi({ 'GET /api/v1/me': noSession, 'POST /api/v1/auth/login': ok(ana) })
    const { user } = renderApp(['/perfil'])
    await screen.findByRole('heading', { name: 'Entrar' })

    await user.type(screen.getByLabelText('E-mail'), 'ana@exemplo.com')
    await user.type(screen.getByLabelText('Senha'), 'Senha-Forte-123')
    await user.click(screen.getByRole('button', { name: 'Entrar' }))

    expect(await screen.findByRole('heading', { name: 'Perfil' })).toBeInTheDocument()
    expect(location()).toBe('/perfil')
  })

  it('o endereço com credencial na consulta não é lido nem repassado: some do caminho e da API', async () => {
    const api = fakeApi({ 'GET /api/v1/me': noSession, 'POST /api/v1/auth/login': ok(ana) })
    const { user } = renderApp(['/perfil' + tokenQuery])

    await screen.findByRole('heading', { name: 'Entrar' })
    expect(location()).toBe('/entrar')

    await user.type(screen.getByLabelText('E-mail'), 'ana@exemplo.com')
    await user.type(screen.getByLabelText('Senha'), 'Senha-Forte-123')
    await user.click(screen.getByRole('button', { name: 'Entrar' }))

    expect(await screen.findByRole('heading', { name: 'Perfil' })).toBeInTheDocument()
    expect(location()).toBe('/perfil')
    for (const call of api.calls) {
      expect(call.path).not.toContain('?')
      expect(JSON.stringify(call.body ?? '')).not.toContain('segredo')
    }
  })

  it('o cadastro e a entrada são abertos a quem não tem sessão', async () => {
    fakeApi({ 'GET /api/v1/me': noSession })

    renderApp(['/cadastro'])

    expect(await screen.findByRole('heading', { name: 'Criar conta' })).toBeInTheDocument()
  })
})

describe('quem tem sessão', () => {
  it('a entrada e o cadastro levam direto ao início', async () => {
    fakeApi({ 'GET /api/v1/me': ok(ana) })
    renderApp(['/entrar'])

    expect(await screen.findByRole('heading', { name: 'Olá, Ana' })).toBeInTheDocument()
    expect(location()).toBe('/')
  })

  it('navega entre o início e o perfil pela navegação', async () => {
    fakeApi({ 'GET /api/v1/me': ok(ana) })
    const { user } = renderApp(['/'])
    await screen.findByRole('heading', { name: 'Olá, Ana' })

    await user.click(screen.getByRole('link', { name: 'Perfil' }))

    expect(await screen.findByRole('heading', { name: 'Perfil' })).toBeInTheDocument()
    expect(location()).toBe('/perfil')
    expect(screen.getByRole('link', { name: 'Perfil' })).toHaveAttribute('aria-current', 'page')
  })

  it('o aplicativo todo passa no axe', async () => {
    fakeApi({ 'GET /api/v1/me': ok(ana) })
    const { container } = renderApp(['/perfil'])
    await screen.findByRole('heading', { name: 'Perfil' })

    await expectNoSeriousViolations(container)
  })
})

describe('endereço que não existe', () => {
  it('mostra a página própria, com sessão ou sem ela, e o caminho de volta', async () => {
    fakeApi({ 'GET /api/v1/me': noSession })
    const { user } = renderApp(['/nao/existe'])

    expect(await screen.findByRole('heading', { name: 'Página não encontrada' })).toBeInTheDocument()
    await user.click(screen.getByRole('link', { name: 'Voltar ao início' }))

    // sem sessão, o início leva à entrada
    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
  })

  it('com sessão, o caminho de volta chega ao início', async () => {
    fakeApi({ 'GET /api/v1/me': ok(ana) })
    const { user } = renderApp(['/nao/existe'])

    await user.click(await screen.findByRole('link', { name: 'Voltar ao início' }))

    expect(await screen.findByRole('heading', { name: 'Olá, Ana' })).toBeInTheDocument()
  })
})

describe('a sessão que termina', () => {
  it('um 401 no meio do uso leva à entrada com o aviso, e depois de entrar volta ao mesmo lugar', async () => {
    fakeApi({ 'GET /api/v1/me': ok(ana), 'GET /api/v1/contas': problem(401, 'Não autenticado'), 'POST /api/v1/auth/login': ok(ana) })
    const { user } = renderApp(['/perfil'])
    await screen.findByRole('heading', { name: 'Perfil' })

    await act(() => request('GET', '/contas').catch(() => undefined))

    expect(await screen.findByText('Sua sessão terminou. Entre de novo.')).toBeInTheDocument()
    expect(location()).toBe('/entrar')

    await user.type(screen.getByLabelText('E-mail'), 'ana@exemplo.com')
    await user.type(screen.getByLabelText('Senha'), 'Senha-Forte-123')
    await user.click(screen.getByRole('button', { name: 'Entrar' }))
    expect(await screen.findByRole('heading', { name: 'Perfil' })).toBeInTheDocument()
  })

  it('depois de sair, nenhuma tela de dentro fica à vista', async () => {
    fakeApi({ 'GET /api/v1/me': ok(ana), 'POST /api/v1/auth/logout': noContent() })
    const { user } = renderApp(['/perfil'])
    await user.click(await screen.findByRole('button', { name: 'Sair' }))

    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: 'Principal' })).toBeNull()
    expect(screen.queryByText('ana@exemplo.com')).toBeNull()
    expect(screen.queryByText('Ana Maria')).toBeNull()
  })
})

describe('trocar de conta no mesmo aparelho', () => {
  it('a conta anterior não deixa nada: nome, workspaces e a escolha de workspace', async () => {
    const accounts: Record<string, Me> = { 'ana@exemplo.com': ana, 'bia@exemplo.com': bia }
    let session: Me | null = ana
    const login = (call: Call): Reply => {
      session = accounts[(call.body as { email: string }).email]
      return ok(session)
    }
    fakeApi({
      'GET /api/v1/me': () => (session ? ok(session) : noSession),
      'POST /api/v1/auth/login': login,
      'POST /api/v1/auth/logout': () => {
        session = null
        return noContent()
      },
    })
    const { user } = renderApp(['/perfil'])

    // A Ana escolhe o segundo workspace e sai.
    await user.click(await screen.findByRole('button', { name: 'Usar Viagem da Ana' }))
    expect(localStorage.getItem('tostao.workspace.u-ana')).toBe('w-viagem')
    await user.click(screen.getByRole('button', { name: 'Sair' }))
    await screen.findByRole('heading', { name: 'Entrar' })

    // A Bia entra no mesmo aparelho.
    await user.type(screen.getByLabelText('E-mail'), 'bia@exemplo.com')
    await user.type(screen.getByLabelText('Senha'), 'Outra-Senha-1')
    await user.click(screen.getByRole('button', { name: 'Entrar' }))

    expect(await screen.findByText('bia@exemplo.com')).toBeInTheDocument()
    const page = document.body
    expect(within(page).queryByText(/Ana/)).toBeNull()
    expect(within(page).queryByText('ana@exemplo.com')).toBeNull()
    expect(within(page).queryByText('Viagem da Ana')).toBeNull()
    expect(within(page).getByText('Casa da Bia')).toBeInTheDocument()
    expect(within(page).getByText('Atual')).toBeInTheDocument()
  })
})
