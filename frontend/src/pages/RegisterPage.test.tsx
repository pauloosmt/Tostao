import { act, screen, waitFor } from '@testing-library/react'
import { Route } from 'react-router'
import { describe, expect, it } from 'vitest'
import type { Me } from '../api/types'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, ok, problem, type Reply } from '../test/fakeApi'
import { renderRoutes } from '../test/renderRoutes'
import { RegisterPage } from './RegisterPage'

const me: Me = { id: 'u1', name: 'Dona Ana', email: 'ana@exemplo.com', workspaces: [] }
const noSession = problem(401, 'Não autenticado')

const routes = (
  <>
    <Route path="/cadastro" element={<RegisterPage />} />
    <Route path="/entrar" element={<h1>Entrar</h1>} />
    <Route path="/" element={<h1>Início</h1>} />
    <Route path="/perfil" element={<h1>Perfil</h1>} />
  </>
)

const heading = () => screen.findByRole('heading', { name: 'Criar conta' })
const name = () => screen.getByLabelText('Nome')
const email = () => screen.getByLabelText('E-mail')
const password = () => screen.getByLabelText('Senha')
const submit = () => screen.getByRole('button', { name: /^(Criar conta|Criando conta…)$/ })

function gate(reply: Reply) {
  let release!: () => void
  const wait = new Promise<void>((resolve) => {
    release = resolve
  })
  return { handler: async () => (await wait, reply), release }
}

async function fillValid(user: ReturnType<typeof renderRoutes>['user']) {
  await user.type(name(), '  Dona Ana  ')
  await user.type(email(), '  ana@exemplo.com  ')
  await user.type(password(), 'Senha-Forte-123')
}

describe('cadastrar', () => {
  it('com sucesso, envia nome e e-mail sem espaços nas pontas e a senha como digitada, e já entra', async () => {
    const api = fakeApi({ 'GET /api/v1/me': noSession, 'POST /api/v1/auth/register': ok(me, 201) })
    const { user } = renderRoutes(routes, ['/cadastro'])
    await heading()

    await fillValid(user)
    await user.click(submit())

    expect(await screen.findByRole('heading', { name: 'Início' })).toBeInTheDocument()
    expect(api.callsTo('POST /api/v1/auth/register')[0].body).toEqual({ name: 'Dona Ana', email: 'ana@exemplo.com', password: 'Senha-Forte-123' })
  })

  it('volta ao lugar pedido antes, quando é um caminho interno seguro', async () => {
    fakeApi({ 'GET /api/v1/me': noSession, 'POST /api/v1/auth/register': ok(me, 201) })
    const { user } = renderRoutes(routes, [{ pathname: '/cadastro', state: { from: { pathname: '/perfil' } } }])
    await heading()

    await fillValid(user)
    await user.click(submit())

    expect(await screen.findByRole('heading', { name: 'Perfil' })).toBeInTheDocument()
  })

  it('quem já tem sessão não vê a tela: vai direto ao início', async () => {
    fakeApi({ 'GET /api/v1/me': ok(me) })
    renderRoutes(routes, ['/cadastro'])

    expect(await screen.findByRole('heading', { name: 'Início' })).toBeInTheDocument()
  })
})

describe('as regras no campo, antes de enviar', () => {
  it('cada campo mostra o seu erro, o foco vai ao primeiro e a API não é chamada', async () => {
    const api = fakeApi({ 'GET /api/v1/me': noSession })
    const { user } = renderRoutes(routes, ['/cadastro'])
    await heading()

    await user.click(submit())

    expect(name()).toHaveAccessibleDescription('Informe seu nome.')
    expect(email()).toHaveAccessibleDescription('Informe seu e-mail.')
    expect(password()).toHaveAccessibleDescription('Informe uma senha.')
    expect(name()).toHaveFocus()
    expect(api.count('POST /api/v1/auth/register')).toBe(0)
  })

  it('o nome longo, o e-mail malformado e a senha fraca são recusados, e o foco vai ao primeiro', async () => {
    const api = fakeApi({ 'GET /api/v1/me': noSession })
    const { user } = renderRoutes(routes, ['/cadastro'])
    await heading()

    await user.type(name(), 'a'.repeat(101))
    await user.type(email(), 'sem-arroba')
    await user.type(password(), 'sosletras')
    await user.click(submit())

    expect(name()).toHaveAccessibleDescription('Use no máximo 100 caracteres.')
    expect(email()).toHaveAccessibleDescription('Informe um e-mail válido.')
    expect(password()).toHaveAccessibleDescription('Inclua ao menos uma letra e um número.')
    expect(name()).toHaveFocus()
    expect(api.count('POST /api/v1/auth/register')).toBe(0)
  })

  it('a senha mostra a regra como dica enquanto não há erro', async () => {
    fakeApi({ 'GET /api/v1/me': noSession })
    renderRoutes(routes, ['/cadastro'])
    await heading()

    expect(password()).toHaveAccessibleDescription('Use de 8 a 72 caracteres, com letra e número.')
    expect(password()).toHaveAttribute('autocomplete', 'new-password')
  })
})

describe('quando o cadastro falha', () => {
  it('409: o e-mail já cadastrado aparece no campo, os outros valores ficam e a senha é limpa', async () => {
    fakeApi({ 'GET /api/v1/me': noSession, 'POST /api/v1/auth/register': problem(409, 'E-mail já cadastrado') })
    const { user } = renderRoutes(routes, ['/cadastro'])
    await heading()

    await fillValid(user)
    await user.click(submit())

    await waitFor(() => expect(email()).toHaveAccessibleDescription('Este e-mail já está cadastrado.'))
    expect(email()).toHaveFocus()
    expect(name()).toHaveValue('  Dona Ana  ')
    expect(email()).toHaveValue('ana@exemplo.com') // (o campo de e-mail já tira os espaços das pontas)
    expect(password()).toHaveValue('')
  })

  it('400 com erros de campo: cada um no seu campo; o que não é da tela vira o aviso geral', async () => {
    fakeApi({
      'GET /api/v1/me': noSession,
      'POST /api/v1/auth/register': problem(400, 'Requisição inválida', {
        errors: [
          { field: 'email', message: 'deve ser um e-mail válido' },
          { field: 'password', message: 'é fraca demais' },
          { field: 'outro', message: 'campo estranho' },
        ],
      }),
    })
    const { user } = renderRoutes(routes, ['/cadastro'])
    await heading()
    await fillValid(user)

    await user.click(submit())

    expect(await screen.findByRole('alert')).toHaveTextContent('campo estranho')
    expect(email()).toHaveAccessibleDescription('deve ser um e-mail válido')
    expect(password()).toHaveAccessibleDescription('é fraca demais')
    expect(email()).toHaveFocus()
  })

  it('400 sem erros de campo: aviso geral', async () => {
    fakeApi({ 'GET /api/v1/me': noSession, 'POST /api/v1/auth/register': problem(400, 'Requisição inválida') })
    const { user } = renderRoutes(routes, ['/cadastro'])
    await heading()
    await fillValid(user)

    await user.click(submit())

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível criar a conta.')
  })

  it('servidor fora do ar: avisa, mantém o que foi digitado e "Tentar de novo" envia outra vez', async () => {
    const api = fakeApi({ 'GET /api/v1/me': noSession, 'POST /api/v1/auth/register': networkError() })
    const { user } = renderRoutes(routes, ['/cadastro'])
    await heading()
    await fillValid(user)

    await user.click(submit())

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível falar com o servidor. Tente de novo.')
    expect(password()).toHaveValue('Senha-Forte-123')

    api.on('POST /api/v1/auth/register', ok(me, 201))
    await user.click(screen.getByRole('button', { name: 'Tentar de novo' }))

    expect(await screen.findByRole('heading', { name: 'Início' })).toBeInTheDocument()
    expect(api.count('POST /api/v1/auth/register')).toBe(2)
  })

  it('um erro 500 também é falha do servidor', async () => {
    fakeApi({ 'GET /api/v1/me': noSession, 'POST /api/v1/auth/register': problem(500, 'Erro interno') })
    const { user } = renderRoutes(routes, ['/cadastro'])
    await heading()
    await fillValid(user)

    await user.click(submit())

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível falar com o servidor.')
  })
})

describe('a tela', () => {
  it('o envio duplicado chama a API uma vez só e mostra que está criando a conta', async () => {
    const pending = gate(ok(me, 201))
    const api = fakeApi({ 'GET /api/v1/me': noSession, 'POST /api/v1/auth/register': pending.handler })
    const { user } = renderRoutes(routes, ['/cadastro'])
    await heading()
    await fillValid(user)

    await user.click(submit())
    await user.click(submit())
    await user.keyboard('{Enter}')

    expect(screen.getByRole('button', { name: 'Criando conta…' })).toBeDisabled()
    await act(async () => pending.release())
    expect(await screen.findByRole('heading', { name: 'Início' })).toBeInTheDocument()
    expect(api.count('POST /api/v1/auth/register')).toBe(1)
  })

  it('leva à entrada', async () => {
    fakeApi({ 'GET /api/v1/me': noSession })
    const { user } = renderRoutes(routes, ['/cadastro'])
    await heading()

    expect(screen.getByRole('link', { name: 'Entrar' })).toHaveAttribute('href', '/entrar')
    await user.click(screen.getByRole('link', { name: 'Entrar' }))

    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
  })

  it('passa no axe, em repouso e com erros', async () => {
    fakeApi({ 'GET /api/v1/me': noSession })
    const { user, container } = renderRoutes(routes, ['/cadastro'])
    await heading()
    await expectNoSeriousViolations(container)

    await user.click(submit())
    await waitFor(() => expect(name()).toHaveAttribute('aria-invalid', 'true'))
    await expectNoSeriousViolations(container)
  })
})
