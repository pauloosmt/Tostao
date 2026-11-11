import { act, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Me } from '../api/types'
import { fakeApi, ok, problem, wsRoute } from '../test/fakeApi'
import { makeAccount, makeBalanceReport, makeCategoryTree } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const CASA = 'w-casa'
const VIAGEM = 'w-viagem'

// A mesma pessoa é dona de um workspace e leitora do outro: o papel vale por workspace.
const me: Me = {
  id: 'u-ana',
  name: 'Ana Maria',
  email: 'ana@exemplo.com',
  workspaces: [
    { id: CASA, name: 'Casa', baseCurrency: 'BRL', role: 'OWNER' },
    { id: VIAGEM, name: 'Viagem', baseCurrency: 'BRL', role: 'VIEWER' },
  ],
}

const contaDaCasa = makeAccount({ name: 'Carteira da Casa', balanceCents: 10000 })
const contaDaViagem = makeAccount({ name: 'Dólares da Viagem', balanceCents: 55000 })
const categoriaDaCasa = makeCategoryTree({ name: 'Moradia', kind: 'EXPENSE' })
const categoriaDaViagem = makeCategoryTree({ name: 'Passagens', kind: 'EXPENSE' })

function openApp(entry: Parameters<typeof renderApp>[0], overrides: Record<string, Parameters<ReturnType<typeof fakeApi>['on']>[1]> = {}) {
  const api = fakeApi({
    'GET /api/v1/me': ok(me),
    [wsRoute('GET', CASA, '/accounts')]: ok([contaDaCasa]),
    [wsRoute('GET', CASA, '/reports/balance')]: ok(makeBalanceReport([contaDaCasa])),
    [wsRoute('GET', VIAGEM, '/accounts')]: ok([contaDaViagem]),
    [wsRoute('GET', VIAGEM, '/reports/balance')]: ok(makeBalanceReport([contaDaViagem])),
    [wsRoute('GET', CASA, '/categories')]: ok([categoriaDaCasa]),
    [wsRoute('GET', VIAGEM, '/categories')]: ok([categoriaDaViagem]),
    ...overrides,
  })
  return { api, ...renderApp(entry) }
}

describe('o papel vale por workspace', () => {
  it('no workspace em que é dona, as ações aparecem; no outro, em que é leitora, nenhuma', async () => {
    const { user } = openApp(['/contas'])
    await screen.findByText('Carteira da Casa')
    expect(screen.getByRole('link', { name: 'Criar conta' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Editar Carteira da Casa' })).toBeInTheDocument()

    await user.click(screen.getByRole('link', { name: 'Perfil' }))
    await user.click(await screen.findByRole('button', { name: 'Usar Viagem' }))
    await user.click(screen.getByRole('link', { name: 'Contas' }))

    expect(await screen.findByText('Dólares da Viagem')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Criar conta' })).toBeNull()
    expect(screen.queryByRole('link', { name: /^Editar / })).toBeNull()
    expect(screen.queryByRole('button', { name: /^Arquivar / })).toBeNull()
  })

  it('os cinco endereços de formulário mostram o aviso de papel ao leitor, sem consultar nada do item', async () => {
    const paths = ['/contas/nova', '/contas/acc-x', '/categorias/nova', '/categorias/cat-x', '/categorias/cat-x/subcategorias/nova']
    for (const path of paths) {
      localStorage.setItem('tostao.workspace.u-ana', VIAGEM) // a pessoa já escolheu o workspace em que é leitora
      const { api, unmount } = openApp([path])

      expect(await screen.findByRole('heading', { name: 'Somente consulta' }), path).toBeInTheDocument()
      expect(screen.queryByLabelText('Nome'), path).toBeNull()
      expect(api.calls.filter((call) => call.method !== 'GET' || /\/(accounts|categories)\/[a-z-]+x(\/|$)/.test(call.path)), path).toEqual([])
      unmount()
    }
  })
})

describe('trocar de workspace', () => {
  it('as listas de contas e de categorias passam a ser as do workspace novo, e nada do anterior fica', async () => {
    const { api, user } = openApp(['/contas'])
    await screen.findByText('Carteira da Casa')

    await user.click(screen.getByRole('link', { name: 'Perfil' }))
    await user.click(await screen.findByRole('button', { name: 'Usar Viagem' }))
    await user.click(screen.getByRole('link', { name: 'Contas' }))

    await screen.findByText('Dólares da Viagem')
    expect(screen.queryByText('Carteira da Casa')).toBeNull()
    expect(api.callsTo(wsRoute('GET', VIAGEM, '/accounts'))).toHaveLength(1)
    expect(api.callsTo(wsRoute('GET', VIAGEM, '/reports/balance'))).toHaveLength(1)

    await user.click(screen.getByRole('link', { name: 'Categorias' }))
    await screen.findByText('Passagens')
    expect(screen.queryByText('Moradia')).toBeNull()
    expect(api.callsTo(wsRoute('GET', VIAGEM, '/categories'))).toHaveLength(1)
  })

  it('voltar ao workspace anterior mostra os dados dele de novo (consultados outra vez, sem cache entre workspaces)', async () => {
    const { api, user } = openApp(['/contas'])
    await screen.findByText('Carteira da Casa')
    await user.click(screen.getByRole('link', { name: 'Perfil' }))
    await user.click(await screen.findByRole('button', { name: 'Usar Viagem' }))
    await user.click(screen.getByRole('link', { name: 'Contas' }))
    await screen.findByText('Dólares da Viagem')

    await user.click(screen.getByRole('link', { name: 'Perfil' }))
    await user.click(await screen.findByRole('button', { name: 'Usar Casa' }))
    await user.click(screen.getByRole('link', { name: 'Contas' }))

    expect(await screen.findByText('Carteira da Casa')).toBeInTheDocument()
    expect(screen.queryByText('Dólares da Viagem')).toBeNull()
    expect(screen.getByRole('link', { name: 'Criar conta' })).toBeInTheDocument()
    expect(api.callsTo(wsRoute('GET', CASA, '/accounts'))).toHaveLength(2)
  })

  it('a resposta atrasada do workspace anterior chega depois e não aparece', async () => {
    let release!: () => void
    const wait = new Promise<void>((resolve) => {
      release = resolve
    })
    const { user } = openApp(['/contas'], {
      [wsRoute('GET', CASA, '/accounts')]: async () => (await wait, ok([contaDaCasa])),
    })
    // a lista da Casa ainda está carregando: a pessoa vai ao perfil, troca de workspace e abre as contas do outro
    await user.click(await screen.findByRole('link', { name: 'Perfil' }))
    await user.click(await screen.findByRole('button', { name: 'Usar Viagem' }))
    await user.click(screen.getByRole('link', { name: 'Contas' }))
    await screen.findByText('Dólares da Viagem')

    await act(async () => release())

    expect(screen.getByText('Dólares da Viagem')).toBeInTheDocument()
    expect(screen.queryByText('Carteira da Casa')).toBeNull()
  })

  it('um endereço de conta ou categoria que não é do workspace atual é "não encontrada", igual a um inexistente', async () => {
    localStorage.setItem('tostao.workspace.u-ana', CASA)
    const gone = problem(404, 'Não encontrada')
    const account = openApp(['/contas/acc-de-outro-workspace'], { [wsRoute('GET', CASA, '/accounts/acc-de-outro-workspace')]: gone })
    expect(await screen.findByRole('heading', { name: 'Conta não encontrada' })).toBeInTheDocument()
    expect(account.api.callsTo(wsRoute('GET', CASA, '/accounts/acc-de-outro-workspace'))).toHaveLength(1)
    account.unmount()

    openApp(['/categorias/cat-de-outro-workspace'], { [wsRoute('GET', CASA, '/categories/cat-de-outro-workspace')]: gone })
    expect(await screen.findByRole('heading', { name: 'Categoria não encontrada' })).toBeInTheDocument()
  })
})

describe('a sessão que termina no meio do uso', () => {
  it('um 401 ao carregar as contas leva à entrada com o aviso de sessão terminada', async () => {
    openApp(['/contas'], { [wsRoute('GET', CASA, '/accounts')]: problem(401, 'Não autenticado') })

    expect(await screen.findByText('Sua sessão terminou. Entre de novo.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/entrar')
  })

  it('um 401 ao carregar as categorias também', async () => {
    openApp(['/categorias'], { [wsRoute('GET', CASA, '/categories')]: problem(401, 'Não autenticado') })

    expect(await screen.findByText('Sua sessão terminou. Entre de novo.')).toBeInTheDocument()
  })
})
