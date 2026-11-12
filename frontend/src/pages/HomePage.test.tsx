import { act, screen, waitFor, within } from '@testing-library/react'
import { Outlet, Route } from 'react-router'
import { describe, expect, it } from 'vitest'
import type { Me, WorkspaceMembership } from '../api/types'
import { useAuth } from '../auth/useAuth'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, noContent, ok, problem, wsRoute } from '../test/fakeApi'
import { makeAccount, makeDashboard } from '../test/fixtures'
import { currentMonth, monthLabel, shiftMonth } from '../dates/months'
import { TransactionFiltersProvider } from '../transactions/filters'
import { useTransactionFilters } from '../transactions/useFilters'
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
      <TransactionFiltersProvider>
        <Outlet />
      </TransactionFiltersProvider>
    </WorkspaceProvider>
  ) : (
    <p>fora</p>
  )
}
// A lista de mentira só mostra o filtro de estado que o Início aplicou ao tocar em "Ver pendentes".
function ListProbe() {
  const { filters } = useTransactionFilters()
  return (
    <>
      <h1>Lista</h1>
      <p data-testid="filtro">{filters.status ?? ''}</p>
    </>
  )
}
const routes = (
  <Route element={<WithSession />}>
    <Route path="/" element={<HomePage />} />
    <Route path="/lancamentos" element={<ListProbe />} />
  </Route>
)

const MES = currentMonth()
const ANTES = shiftMonth(MES, -1) as string
const grupo = (name: string, totalCents: number, percent: number, categoryId: string | null = name) => ({ categoryId, name, directCents: totalCents, totalCents, percent, children: [] })
const base = makeDashboard([])

describe('o início', () => {
  it('mostra a Carteira com o saldo e o fecho do mês, os totais com a variação, os gastos por categoria e as contas', async () => {
    const contas = [makeAccount({ name: 'Carteira', type: 'CASH', balanceCents: 15000 }), makeAccount({ name: 'Reserva', type: 'SAVINGS', balanceCents: 100000 })]
    fakeApi({
      'GET /api/v1/me': ok(ana([casa, viagem])),
      ...homeRoutes(contas, 'w-casa', {
        totals: { incomeCents: 500000, expenseCents: 327640, resultCents: 172360 },
        comparison: {
          previousPeriod: { id: ANTES },
          income: { previousCents: 400000, changeCents: 100000, changePercent: 25 },
          expense: { previousCents: 400000, changeCents: -72360, changePercent: -18.09 },
          result: base.comparison.result,
        },
        expenseByCategory: [grupo('Alimentação', 200000, 61.04), grupo('Sem categoria', 127640, 38.96, null)],
      }),
    })
    const { container } = renderRoutes(routes, ['/'])

    expect(await screen.findByRole('heading', { level: 1, name: 'Olá, Ana' })).toBeInTheDocument()
    expect(await screen.findByText('Saldo total')).toBeInTheDocument()
    expect(container.querySelector('.tx-wallet__amount')).toHaveTextContent(/1\.150,00/)
    expect(container.querySelector('.tx-wallet__clasp')).toHaveTextContent(/Sobram R\$\s*1\.723,60 em /)
    expect(screen.getByText('Casa · Dono')).toBeInTheDocument()
    const totals = within(screen.getByRole('region', { name: 'Resumo do mês' }))
    expect(totals.getByText('Receitas').closest('.tx-stat')).toHaveTextContent(/5\.000,00/)
    expect(totals.getByText(/^25% a mais que em /)).toBeInTheDocument()
    expect(totals.getByText(/^18,1% a menos que em /)).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: 'Alimentação' })).toHaveAttribute('aria-valuenow', '61.04')
    expect(screen.getByText('Sem categoria')).toBeInTheDocument()
    expect(screen.getByText('Reserva')).toBeInTheDocument()
    await expectNoSeriousViolations(container)
  })

  it('o mês no vermelho diz "Faltam"; trocar o mês consulta o mês novo e nunca mostra o anterior no lugar', async () => {
    let release!: () => void
    const wait = new Promise<void>((resolve) => {
      release = resolve
    })
    const api = fakeApi({
      'GET /api/v1/me': ok(ana([casa])),
      [wsRoute('GET', 'w-casa', '/dashboard')]: async (call) => {
        if (call.path.includes(`month=${ANTES}`)) await wait
        return ok(makeDashboard([makeAccount({ name: 'Carteira', balanceCents: 1000 })], { totals: { incomeCents: 0, expenseCents: 20000, resultCents: -20000 } }))
      },
    })
    const { container, user } = renderRoutes(routes, ['/'])
    expect(await screen.findByText(monthLabel(MES))).toBeInTheDocument()
    await waitFor(() => expect(container.querySelector('.tx-wallet__clasp')).toHaveTextContent(/Faltam R\$\s*200,00/))

    await user.click(screen.getByRole('button', { name: 'Mês anterior' }))

    expect(screen.getByText(monthLabel(ANTES))).toBeInTheDocument()
    expect(screen.getByText('Carregando o resumo do mês…')).toBeInTheDocument()
    expect(container.querySelector('.tx-wallet__clasp')).toBeNull()
    await act(async () => release())
    expect(await screen.findByText('Saldo total')).toBeInTheDocument()
    expect(api.callsTo(wsRoute('GET', 'w-casa', '/dashboard')).map((call) => call.path.split('month=')[1])).toEqual([MES, ANTES])
  })

  it('o pendente aparece à parte, e "Ver pendentes" abre a lista já filtrada por pendente', async () => {
    fakeApi({ 'GET /api/v1/me': ok(ana([casa])), ...homeRoutes(undefined, 'w-casa', { pending: { incomeCents: 0, expenseCents: 9900, count: 2 } }) })
    const { user } = renderRoutes(routes, ['/'])

    expect(await screen.findByText('2 pendentes')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Ver pendentes' }))

    expect(await screen.findByRole('heading', { name: 'Lista' })).toBeInTheDocument()
    expect(screen.getByTestId('filtro')).toHaveTextContent('PENDING')
  })

  it('mês sem lançamentos: convida a lançar (quem pode escrever)', async () => {
    fakeApi({ 'GET /api/v1/me': ok(ana([casa])), ...homeRoutes() })
    renderRoutes(routes, ['/'])

    expect(await screen.findByText('Nenhum lançamento neste mês')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Novo lançamento' })).toHaveAttribute('href', '/lancamentos/novo')
    expect(screen.getByText('Nenhuma despesa neste mês.')).toBeInTheDocument()
  })

  it('sem contas, a Carteira convida a criar a primeira; o leitor não vê o botão', async () => {
    fakeApi({ 'GET /api/v1/me': ok(ana([casa])), ...homeRoutes([]) })
    const owner = renderRoutes(routes, ['/'])
    expect(await screen.findByText('Crie sua primeira conta')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Criar conta' })).toHaveAttribute('href', '/contas/nova')
    owner.unmount()

    localStorage.setItem(workspaceKey('u-ana'), 'w-viagem')
    fakeApi({ 'GET /api/v1/me': ok(ana([casa, viagem])), ...homeRoutes([], 'w-viagem') })
    renderRoutes(routes, ['/'])
    expect(await screen.findByText('Crie sua primeira conta')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Criar conta' })).not.toBeInTheDocument()
  })

  it('se o resumo não carrega, avisa com "Tentar de novo"', async () => {
    fakeApi({ 'GET /api/v1/me': ok(ana([casa])), [wsRoute('GET', 'w-casa', '/dashboard')]: networkError() })
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
