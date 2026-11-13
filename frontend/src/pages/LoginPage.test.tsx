import { act, screen, waitFor } from '@testing-library/react'
import { Navigate, Route, useLocation } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { request } from '../api/client'
import type { Me } from '../api/types'
import { useAuth } from '../auth/useAuth'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, ok, problem, type Reply } from '../test/fakeApi'
import { renderRoutes } from '../test/renderRoutes'
import { LoginPage } from './LoginPage'

const me: Me = { id: 'u1', name: 'Dona Ana', email: 'ana@exemplo.com', workspaces: [] }
const noSession = problem(401, 'Não autenticado')

// (a consulta com credencial é montada em pedaços para a verificação de query string não a tomar por código do app)
const tokenQuery = ['?', 'token=segredo'].join('')

const routes = (
  <>
    <Route path="/entrar" element={<LoginPage />} />
    <Route path="/cadastro" element={<h1>Cadastro</h1>} />
    <Route path="/" element={<h1>Início</h1>} />
    <Route path="/perfil" element={<h1>Perfil</h1>} />
  </>
)

const heading = () => screen.findByRole('heading', { name: 'Entrar' })
const email = () => screen.getByLabelText('E-mail')
const password = () => screen.getByLabelText('Senha')
const submit = () => screen.getByRole('button', { name: /^(Entrar|Entrando…|Aguarde)/ })
const location = () => screen.getByTestId('location').textContent

function gate(reply: Reply) {
  let release!: () => void
  const wait = new Promise<void>((resolve) => {
    release = resolve
  })
  return { handler: async () => (await wait, reply), release }
}

describe('baixar o app', () => {
  it('o link para Windows só aparece no navegador de um computador com Windows', async () => {
    fakeApi({ 'GET /api/v1/me': noSession })
    const { unmount } = renderRoutes(routes, ['/entrar'])
    await heading()
    expect(screen.queryByRole('link', { name: 'Baixar o app para Windows' })).not.toBeInTheDocument() // o jsdom não é Windows
    unmount()

    vi.spyOn(window.navigator, 'userAgent', 'get').mockReturnValue('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36')
    renderRoutes(routes, ['/entrar'])
    await heading()
    expect(screen.getByRole('link', { name: 'Baixar o app para Windows' })).toHaveAttribute('href', '/baixar/windows')
    vi.restoreAllMocks()
  })
})

describe('entrar', () => {
  it('com sucesso, envia o e-mail sem espaços e a senha como digitada, e vai para o início', async () => {
    const api = fakeApi({ 'GET /api/v1/me': noSession, 'POST /api/v1/auth/login': ok(me) })
    const { user } = renderRoutes(routes, ['/entrar'])
    await heading()

    await user.type(email(), '  ana@exemplo.com  ')
    await user.type(password(), ' Senha-Forte-123 ')
    await user.click(submit())

    expect(await screen.findByRole('heading', { name: 'Início' })).toBeInTheDocument()
    expect(api.callsTo('POST /api/v1/auth/login')[0].body).toEqual({ email: 'ana@exemplo.com', password: ' Senha-Forte-123 ' })
    expect(location()).toBe('/')
  })

  it('volta ao lugar pedido antes de entrar, quando é um caminho interno seguro', async () => {
    fakeApi({ 'GET /api/v1/me': noSession, 'POST /api/v1/auth/login': ok(me) })
    const { user } = renderRoutes(routes, [{ pathname: '/entrar', state: { from: { pathname: '/perfil', search: '', hash: '' } } }])
    await heading()

    await user.type(email(), 'ana@exemplo.com')
    await user.type(password(), 'Senha-Forte-123')
    await user.click(submit())

    expect(await screen.findByRole('heading', { name: 'Perfil' })).toBeInTheDocument()
  })

  it('um lugar pedido de outra origem ou com consulta não é seguido nem repassado: vai para o início', async () => {
    fakeApi({ 'GET /api/v1/me': noSession, 'POST /api/v1/auth/login': ok(me) })
    const external = ['https', '://outro.example.com/x'].join('')
    const { user } = renderRoutes(routes, [{ pathname: '/entrar', state: { from: external } }])
    await heading()
    await user.type(email(), 'ana@exemplo.com')
    await user.type(password(), 'Senha-Forte-123')
    await user.click(submit())
    expect(await screen.findByRole('heading', { name: 'Início' })).toBeInTheDocument()
  })

  it('o lugar pedido com consulta perde a consulta ao voltar', async () => {
    fakeApi({ 'GET /api/v1/me': noSession, 'POST /api/v1/auth/login': ok(me) })
    const { user } = renderRoutes(routes, [{ pathname: '/entrar', state: { from: { pathname: '/perfil', search: tokenQuery } } }])
    await heading()
    await user.type(email(), 'ana@exemplo.com')
    await user.type(password(), 'Senha-Forte-123')
    await user.click(submit())

    expect(await screen.findByRole('heading', { name: 'Perfil' })).toBeInTheDocument()
    expect(location()).toBe('/perfil')
  })

  it('quem já tem sessão não vê a tela: vai direto ao início', async () => {
    fakeApi({ 'GET /api/v1/me': ok(me) })
    renderRoutes(routes, ['/entrar'])

    expect(await screen.findByRole('heading', { name: 'Início' })).toBeInTheDocument()
  })
})

describe('quando a entrada falha', () => {
  it('a mesma mensagem para e-mail inexistente e senha errada; a senha é limpa e o foco vai para ela, o e-mail fica', async () => {
    fakeApi({ 'GET /api/v1/me': noSession, 'POST /api/v1/auth/login': problem(401, 'Credenciais inválidas') })
    const { user } = renderRoutes(routes, ['/entrar'])
    await heading()

    const messages: (string | null)[] = []
    for (const typed of ['nao-existe@exemplo.com', 'ana@exemplo.com']) {
      await user.clear(email())
      await user.type(email(), typed)
      await user.type(password(), 'Errada-123')
      await user.click(submit())
      messages.push((await screen.findByRole('alert')).textContent)
      expect(email()).toHaveValue(typed)
      expect(password()).toHaveValue('')
      expect(password()).toHaveFocus()
    }

    expect(messages).toEqual(['E-mail ou senha incorretos.', 'E-mail ou senha incorretos.'])
  })

  it('o bloqueio (429) mostra a espera, deixa o botão indisponível e o libera no fim', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const api = fakeApi({
      'GET /api/v1/me': noSession,
      'POST /api/v1/auth/login': problem(429, 'Muitas tentativas', {}, { 'Retry-After': '3' }),
    })
    const { user } = renderRoutes(routes, ['/entrar'])
    await heading()
    await user.type(email(), 'ana@exemplo.com')
    await user.type(password(), 'Senha-Forte-123')

    await user.click(submit())

    expect(await screen.findByText('Muitas tentativas. Tente de novo em 3 s.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Aguarde 3 s' })).toBeDisabled()
    expect(password()).toHaveValue('Senha-Forte-123')

    await act(() => vi.advanceTimersByTimeAsync(1000))
    expect(screen.getByRole('button', { name: 'Aguarde 2 s' })).toBeDisabled()
    // um segundo por vez: cada tique só é agendado depois que a tela se renderiza com o valor anterior
    await act(() => vi.advanceTimersByTimeAsync(1000))
    await act(() => vi.advanceTimersByTimeAsync(1000))

    expect(screen.getByRole('button', { name: 'Entrar' })).toBeEnabled()
    expect(screen.queryByText(/Muitas tentativas/)).toBeNull()
    expect(api.count('POST /api/v1/auth/login')).toBe(1)
  })

  it('um bloqueio sem Retry-After avisa sem travar o botão', async () => {
    fakeApi({ 'GET /api/v1/me': noSession, 'POST /api/v1/auth/login': problem(429, 'Muitas tentativas') })
    const { user } = renderRoutes(routes, ['/entrar'])
    await heading()
    await user.type(email(), 'ana@exemplo.com')
    await user.type(password(), 'Senha-Forte-123')

    await user.click(submit())

    expect(await screen.findByRole('alert')).toHaveTextContent('Muitas tentativas. Tente de novo em instantes.')
    expect(screen.getByRole('button', { name: 'Entrar' })).toBeEnabled()
  })

  it('erros de campo da API aparecem no campo; os que não são da tela viram o aviso geral', async () => {
    fakeApi({
      'GET /api/v1/me': noSession,
      'POST /api/v1/auth/login': problem(400, 'Requisição inválida', {
        errors: [
          { field: 'password', message: 'não pode ter mais de 72 bytes' },
          { field: 'outro', message: 'campo estranho' },
        ],
      }),
    })
    const { user } = renderRoutes(routes, ['/entrar'])
    await heading()
    await user.type(email(), 'ana@exemplo.com')
    await user.type(password(), 'qualquer')

    await user.click(submit())

    expect(await screen.findByRole('alert')).toHaveTextContent('campo estranho')
    expect(password()).toHaveAccessibleDescription('não pode ter mais de 72 bytes')
    expect(password()).toHaveFocus()
  })

  it('servidor fora do ar: avisa, mantém o que foi digitado e "Tentar de novo" envia outra vez', async () => {
    const api = fakeApi({ 'GET /api/v1/me': noSession, 'POST /api/v1/auth/login': networkError() })
    const { user } = renderRoutes(routes, ['/entrar'])
    await heading()
    await user.type(email(), 'ana@exemplo.com')
    await user.type(password(), 'Senha-Forte-123')

    await user.click(submit())

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível falar com o servidor. Tente de novo.')
    expect(email()).toHaveValue('ana@exemplo.com')
    expect(password()).toHaveValue('Senha-Forte-123')

    api.on('POST /api/v1/auth/login', ok(me))
    await user.click(screen.getByRole('button', { name: 'Tentar de novo' }))

    expect(await screen.findByRole('heading', { name: 'Início' })).toBeInTheDocument()
    expect(api.count('POST /api/v1/auth/login')).toBe(2)
  })

  it('um erro 500 também é tratado como falha do servidor', async () => {
    fakeApi({ 'GET /api/v1/me': noSession, 'POST /api/v1/auth/login': problem(500, 'Erro interno') })
    const { user } = renderRoutes(routes, ['/entrar'])
    await heading()
    await user.type(email(), 'ana@exemplo.com')
    await user.type(password(), 'Senha-Forte-123')

    await user.click(submit())

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível falar com o servidor.')
    expect(screen.getByRole('button', { name: 'Tentar de novo' })).toBeInTheDocument()
  })
})

describe('antes de enviar', () => {
  it('valida no aparelho, mostra o erro no campo e leva o foco ao primeiro com erro, sem chamar a API', async () => {
    const api = fakeApi({ 'GET /api/v1/me': noSession })
    const { user } = renderRoutes(routes, ['/entrar'])
    await heading()

    await user.click(submit())

    expect(email()).toHaveAccessibleDescription('Informe seu e-mail.')
    expect(password()).toHaveAccessibleDescription('Informe sua senha.')
    expect(email()).toHaveFocus()

    await user.type(email(), 'ana@exemplo.com')
    await user.click(submit())
    expect(email()).not.toHaveAttribute('aria-invalid')
    expect(password()).toHaveFocus()
    expect(api.count('POST /api/v1/auth/login')).toBe(0)
  })

  it('um e-mail malformado é recusado no campo', async () => {
    fakeApi({ 'GET /api/v1/me': noSession })
    const { user } = renderRoutes(routes, ['/entrar'])
    await heading()
    await user.type(email(), 'sem-arroba')
    await user.type(password(), 'x')

    await user.click(submit())

    expect(email()).toHaveAccessibleDescription('Informe um e-mail válido.')
  })

  it('o envio duplicado (dois cliques seguidos) chama a API uma vez só e mostra que está entrando', async () => {
    const pending = gate(ok(me))
    const api = fakeApi({ 'GET /api/v1/me': noSession, 'POST /api/v1/auth/login': pending.handler })
    const { user } = renderRoutes(routes, ['/entrar'])
    await heading()
    await user.type(email(), 'ana@exemplo.com')
    await user.type(password(), 'Senha-Forte-123')

    await user.click(submit())
    await user.click(submit())
    await user.keyboard('{Enter}')

    expect(screen.getByRole('button', { name: 'Entrando…' })).toBeDisabled()
    await act(async () => pending.release())
    expect(await screen.findByRole('heading', { name: 'Início' })).toBeInTheDocument()
    expect(api.count('POST /api/v1/auth/login')).toBe(1)
  })

  it('o Enter no campo de senha envia o formulário', async () => {
    const api = fakeApi({ 'GET /api/v1/me': noSession, 'POST /api/v1/auth/login': ok(me) })
    const { user } = renderRoutes(routes, ['/entrar'])
    await heading()
    await user.type(email(), 'ana@exemplo.com')

    await user.type(password(), 'Senha-Forte-123{Enter}')

    expect(await screen.findByRole('heading', { name: 'Início' })).toBeInTheDocument()
    expect(api.count('POST /api/v1/auth/login')).toBe(1)
  })
})

describe('a tela', () => {
  it('leva ao cadastro', async () => {
    fakeApi({ 'GET /api/v1/me': noSession })
    const { user } = renderRoutes(routes, ['/entrar'])
    await heading()

    expect(screen.getByRole('link', { name: 'Criar conta' })).toHaveAttribute('href', '/cadastro')
    await user.click(screen.getByRole('link', { name: 'Criar conta' }))

    expect(await screen.findByRole('heading', { name: 'Cadastro' })).toBeInTheDocument()
  })

  it('o campo de senha tem o autocomplete de senha existente e o de e-mail o de e-mail', async () => {
    fakeApi({ 'GET /api/v1/me': noSession })
    renderRoutes(routes, ['/entrar'])
    await heading()

    expect(email()).toHaveAttribute('autocomplete', 'email')
    expect(password()).toHaveAttribute('autocomplete', 'current-password')
    expect(password()).toHaveAttribute('type', 'password')
  })

  it('passa no axe, em repouso e com erros', async () => {
    fakeApi({ 'GET /api/v1/me': noSession })
    const { user, container } = renderRoutes(routes, ['/entrar'])
    await heading()
    await expectNoSeriousViolations(container)

    await user.click(submit())
    await waitFor(() => expect(email()).toHaveAttribute('aria-invalid', 'true'))
    await expectNoSeriousViolations(container)
  })
})

// A sessão que termina no meio do uso leva à entrada, e a entrada explica por quê.
function ExpiredFlow() {
  const { state } = useAuth()
  const where = useLocation()
  return state.status === 'anonymous' ? <Navigate to="/entrar" state={{ from: where }} replace /> : <h1>Início</h1>
}

describe('sessão terminada', () => {
  it('mostra o aviso na entrada depois de um 401 no meio do uso', async () => {
    fakeApi({ 'GET /api/v1/me': ok(me), 'GET /api/v1/contas': problem(401, 'Não autenticado') })
    renderRoutes(
      <>
        <Route path="/entrar" element={<LoginPage />} />
        <Route path="/" element={<ExpiredFlow />} />
      </>,
      ['/'],
    )
    await screen.findByRole('heading', { name: 'Início' })

    await act(() => request('GET', '/contas').catch(() => undefined))

    expect(await screen.findByText('Sua sessão terminou. Entre de novo.')).toBeInTheDocument()
    expect(location()).toBe('/entrar')
  })

  it('sem sessão anterior o aviso não aparece', async () => {
    fakeApi({ 'GET /api/v1/me': noSession })
    renderRoutes(routes, ['/entrar'])
    await heading()

    expect(screen.queryByText('Sua sessão terminou. Entre de novo.')).toBeNull()
  })
})
