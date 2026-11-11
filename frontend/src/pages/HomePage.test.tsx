import { screen } from '@testing-library/react'
import { Route } from 'react-router'
import { describe, expect, it } from 'vitest'
import type { Me, WorkspaceMembership } from '../api/types'
import { useAuth } from '../auth/useAuth'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, noContent, ok, problem } from '../test/fakeApi'
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
  it('cumprimenta pelo primeiro nome e mostra o workspace atual com o papel em português', async () => {
    fakeApi({ 'GET /api/v1/me': ok(ana([casa, viagem])) })
    renderRoutes(routes, ['/'])

    expect(await screen.findByRole('heading', { name: 'Olá, Ana' })).toBeInTheDocument()
    expect(screen.getByText('Casa')).toBeInTheDocument()
    expect(screen.getByText('Dono')).toBeInTheDocument()
    expect(screen.getByText('Moeda base: BRL')).toBeInTheDocument()
  })

  it('mostra o workspace que a pessoa escolheu neste aparelho', async () => {
    localStorage.setItem(workspaceKey('u-ana'), 'w-viagem')
    fakeApi({ 'GET /api/v1/me': ok(ana([casa, viagem])) })
    renderRoutes(routes, ['/'])

    expect(await screen.findByText('Viagem')).toBeInTheDocument()
    expect(screen.getByText('Leitor')).toBeInTheDocument()
  })

  it('tem um estado vazio que explica o que virá, nunca uma tela vazia', async () => {
    fakeApi({ 'GET /api/v1/me': ok(ana([casa])) })
    const { container } = renderRoutes(routes, ['/'])

    expect(await screen.findByText('Ainda não há nada por aqui')).toBeInTheDocument()
    await expectNoSeriousViolations(container)
  })

  it('um nome só ou com espaços sobrando também é cumprimentado', async () => {
    fakeApi({ 'GET /api/v1/me': ok({ ...ana([casa]), name: '  Bia  ' }) })
    renderRoutes(routes, ['/'])

    expect(await screen.findByRole('heading', { name: 'Olá, Bia' })).toBeInTheDocument()
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
