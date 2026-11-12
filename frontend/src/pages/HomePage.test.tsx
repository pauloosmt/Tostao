import { screen } from '@testing-library/react'
import { Route } from 'react-router'
import { describe, expect, it } from 'vitest'
import type { Me, WorkspaceMembership } from '../api/types'
import { useAuth } from '../auth/useAuth'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, noContent, ok, problem, wsRoute } from '../test/fakeApi'
import { makeAccount } from '../test/fixtures'
import { homeRoutes } from '../test/homeRoutes'
import { renderRoutes } from '../test/renderRoutes'
import { WorkspaceProvider } from '../workspace/WorkspaceContext'
import { workspaceKey } from '../storage/local'
import { roleLabel } from '../workspace/roles'
import { HomePage } from './HomePage'
import { NotFoundPage } from './NotFoundPage'

const casa: WorkspaceMembership = { id: 'w-casa', name: 'Casa', baseCurrency: 'BRL', role: 'OWNER' }
const viagem: WorkspaceMembership = { id: 'w-viagem', name: 'Viagem', baseCurrency: 'BRL', role: 'VIEWER' }
const ana = (workspaces: WorkspaceMembership[]): Me => ({ id: 'u-ana', name: 'Ana Maria Souza', email: 'ana@exemplo.com', workspaces })

// O que o aplicativo faz por fora da tela: só renderiza a tela com sessão, dentro do provider de workspace.
function WithSession() {
  const { state } = useAuth()
  return state.status === 'authenticated' ? (
    <WorkspaceProvider me={state.me}>
      <HomePage />
    </WorkspaceProvider>
  ) : (
    <p>fora</p>
  )
}
const routes = <Route path="/" element={<WithSession />} />

describe('o início', () => {
  it('cumprimenta pelo primeiro nome e mostra a Carteira com o saldo total e as contas', async () => {
    const contas = [makeAccount({ name: 'Carteira', type: 'CASH', balanceCents: 15000 }), makeAccount({ name: 'Reserva', type: 'SAVINGS', balanceCents: 100000 })]
    fakeApi({ 'GET /api/v1/me': ok(ana([casa, viagem])), ...homeRoutes(contas) })
    const { container } = renderRoutes(routes, ['/'])

    expect(await screen.findByRole('heading', { level: 1, name: 'Olá, Ana' })).toBeInTheDocument()
    expect(await screen.findByText('Saldo total')).toBeInTheDocument()
    expect(container.querySelector('.tx-wallet__amount')).toHaveTextContent(/1.150,00/)
    expect(screen.getByText('Casa · Dono')).toBeInTheDocument()
    expect(screen.getByText('Carteira')).toBeInTheDocument()
    expect(screen.getByText('Reserva')).toBeInTheDocument()
    await expectNoSeriousViolations(container)
  })

  it('sem contas, a Carteira convida a criar a primeira (quem pode escrever)', async () => {
    fakeApi({ 'GET /api/v1/me': ok(ana([casa])), ...homeRoutes([]) })
    renderRoutes(routes, ['/'])

    expect(await screen.findByText('Crie sua primeira conta')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Criar conta' })).toHaveAttribute('href', '/contas/nova')
  })

  it('o leitor sem contas não vê o botão de criar', async () => {
    localStorage.setItem(workspaceKey('u-ana'), 'w-viagem')
    fakeApi({ 'GET /api/v1/me': ok(ana([casa, viagem])), ...homeRoutes([], 'w-viagem') })
    renderRoutes(routes, ['/'])

    expect(await screen.findByText('Crie sua primeira conta')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Criar conta' })).not.toBeInTheDocument()
  })

  it('se as contas não carregam, avisa com "Tentar de novo"', async () => {
    fakeApi({ 'GET /api/v1/me': ok(ana([casa])), [wsRoute('GET', 'w-casa', '/accounts')]: networkError(), [wsRoute('GET', 'w-casa', '/reports/balance')]: networkError() })
    renderRoutes(routes, ['/'])

    expect(await screen.findByRole('button', { name: 'Tentar de novo' })).toBeInTheDocument()
  })
})

describe('sem nenhum workspace', () => {
  it('explica e oferece "Sair", que encerra a sessão', async () => {
    const api = fakeApi({ 'GET /api/v1/me': ok(ana([])), 'POST /api/v1/auth/logout': noContent() })
    const { user } = renderRoutes(routes, ['/'])

    expect(await screen.findByText(/não participa de nenhum workspace/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Sair' }))

    expect(await screen.findByText('fora')).toBeInTheDocument()
    expect(api.count('POST /api/v1/auth/logout')).toBe(1)
  })

  it('se a saída falha, a pessoa continua dentro e vê o aviso', async () => {
    fakeApi({ 'GET /api/v1/me': ok(ana([])), 'POST /api/v1/auth/logout': problem(500, 'Erro') })
    const { user } = renderRoutes(routes, ['/'])
    await user.click(await screen.findByRole('button', { name: 'Sair' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível sair. Tente de novo.')
    expect(screen.getByRole('button', { name: 'Sair' })).toBeInTheDocument()
  })
})

describe('os papéis em português', () => {
  it('dono, editor e leitor', () => {
    expect([roleLabel('OWNER'), roleLabel('EDITOR'), roleLabel('VIEWER')]).toEqual(['Dono', 'Editor', 'Leitor'])
  })

  it('um papel desconhecido aparece como veio, nunca em branco', () => {
    expect(roleLabel('AUDITOR' as never)).toBe('AUDITOR')
  })
})

describe('página não encontrada', () => {
  it('tem a página própria, com o caminho de volta', async () => {
    fakeApi({ 'GET /api/v1/me': ok(ana([casa])) })
    const { container, user } = renderRoutes(
      <>
        <Route path="/" element={<h1>Início</h1>} />
        <Route path="*" element={<NotFoundPage />} />
      </>,
      ['/uma/pagina/que/nao/existe'],
    )

    expect(await screen.findByRole('heading', { name: 'Página não encontrada' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Voltar ao início' })).toHaveAttribute('href', '/')
    await expectNoSeriousViolations(container)

    await user.click(screen.getByRole('link', { name: 'Voltar ao início' }))
    expect(await screen.findByRole('heading', { name: 'Início' })).toBeInTheDocument()
  })
})
