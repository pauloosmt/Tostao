import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Account, Me } from '../api/types'
import { FAILURE_MESSAGES } from '../forms/apiErrors'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, ok, problem, wsRoute } from '../test/fakeApi'
import { installTransactions } from '../test/fakeTransactions'
import { makeAccount, makeCategory, makeCategoryTree, makeTransaction } from '../test/fixtures'
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

const accountOf = (id: string, name: string): Account => makeAccount({ id, name })
const mercado = makeCategory({ id: 'c-mercado', name: 'Mercado' })
const categoriesOf = () => [makeCategoryTree({ id: 'c-alim', name: 'Alimentação', kind: 'EXPENSE' }, [mercado])]

const lunch = makeTransaction({ id: 'tx-casa', accountId: 'a-casa', categoryId: 'c-mercado', amountCents: -3590, description: 'Almoço da Casa', occurredOn: '2026-11-03' })
const flight = makeTransaction({ id: 'tx-viagem', accountId: 'a-viagem', categoryId: 'c-mercado', amountCents: -90000, description: 'Passagem da Viagem', occurredOn: '2026-11-04' })

function openApp(entry: Parameters<typeof renderApp>[0] = ['/lancamentos']) {
  const api = fakeApi({
    'GET /api/v1/me': ok(me),
    [wsRoute('GET', CASA, '/accounts')]: ok([accountOf('a-casa', 'Carteira da Casa')]),
    [wsRoute('GET', VIAGEM, '/accounts')]: ok([accountOf('a-viagem', 'Dólares da Viagem')]),
    [wsRoute('GET', CASA, '/categories')]: ok(categoriesOf()),
    [wsRoute('GET', VIAGEM, '/categories')]: ok(categoriesOf()),
  })
  const casa = installTransactions(api, [lunch], { workspaceId: CASA, categories: categoriesOf() })
  const viagem = installTransactions(api, [flight], { workspaceId: VIAGEM, categories: categoriesOf() })
  return { api, casa, viagem, ...renderApp(entry) }
}
type Ctx = ReturnType<typeof openApp>

const switchTo = async (user: Ctx['user'], name: string) => {
  await user.click(screen.getByRole('link', { name: /Ana Maria/ }))
  await user.click(await screen.findByRole('button', { name: `Usar ${name}` }))
  await user.click(screen.getByRole('link', { name: 'Lançamentos' }))
}

describe('o papel vale por workspace', () => {
  it('na Casa (dona) as ações aparecem; na Viagem (leitora) nenhuma, e a linha abre só para consulta', async () => {
    const { user } = openApp()
    await screen.findByText('Almoço da Casa')
    expect(screen.getByRole('link', { name: 'Novo lançamento' })).toBeInTheDocument()

    await switchTo(user, 'Viagem')

    expect(await screen.findByText('Passagem da Viagem')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Novo lançamento' })).toBeNull()
    await user.click(screen.getByRole('button', { name: /Passagem da Viagem/ }))
    expect(await screen.findByRole('heading', { name: 'Lançamento' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^(Salvar|Efetivar|Excluir)/ })).toBeNull()
    expect(screen.queryByLabelText('Valor')).toBeNull()
  })

  it('o leitor que abre /lancamentos/novo vê o aviso de papel, sem formulário e sem consultar nada', async () => {
    localStorage.setItem('tostao.workspace.u-ana', VIAGEM)
    const { api, viagem } = openApp(['/lancamentos/novo'])

    expect(await screen.findByText(/só para consulta/)).toBeInTheDocument()
    expect(screen.getByTestId('location').textContent).toBe('/lancamentos')
    expect(screen.queryByLabelText('Valor')).toBeNull()
    expect(api.calls.filter((call) => call.method !== 'GET')).toEqual([])
    expect(viagem.store).toHaveLength(1)
  })

  it('o leitor nunca envia nada: nenhuma chamada de escrita em toda a consulta', async () => {
    localStorage.setItem('tostao.workspace.u-ana', VIAGEM)
    const { api, user } = openApp(['/lancamentos'])
    await screen.findByText('Passagem da Viagem')
    await user.click(screen.getByRole('button', { name: /Passagem da Viagem/ }))
    await screen.findByRole('heading', { name: 'Lançamento' })

    expect(api.calls.filter((call) => ['POST', 'PUT', 'DELETE'].includes(call.method))).toEqual([])
  })
})

describe('trocar de workspace', () => {
  it('a lista, as contas e as categorias passam a ser as do novo, e nada do anterior fica', async () => {
    const { api, user } = openApp()
    await screen.findByText('Almoço da Casa')

    await switchTo(user, 'Viagem')

    await screen.findByText('Passagem da Viagem')
    expect(screen.queryByText('Almoço da Casa')).toBeNull()
    expect(screen.getByRole('button', { name: /Passagem da Viagem/ })).toHaveTextContent('Dólares da Viagem')
    expect(api.callsTo(wsRoute('GET', VIAGEM, '/transactions'))).toHaveLength(1)
    expect(api.callsTo(wsRoute('GET', VIAGEM, '/accounts'))).toHaveLength(1)
    expect(api.callsTo(wsRoute('GET', VIAGEM, '/categories'))).toHaveLength(1)
  })

  it('os filtros são zerados ao trocar, e voltar ao anterior não os traz de volta', async () => {
    const { api, user } = openApp()
    await screen.findByText('Almoço da Casa')
    await user.click(screen.getByRole('button', { name: /^Filtros/ }))
    fireEvent.change(await screen.findByLabelText('De'), { target: { value: '2026-11-01' } })
    await user.click(screen.getByRole('button', { name: 'Aplicar filtros' }))
    await screen.findByText('1 lançamento')
    expect(screen.getByRole('button', { name: /^Filtros/ })).toHaveTextContent('Filtros (1)')

    await switchTo(user, 'Viagem')
    await screen.findByText('Passagem da Viagem')
    expect(screen.getByRole('button', { name: /^Filtros/ })).toHaveTextContent(/^Filtros$/)
    const viagemCall = api.callsTo(wsRoute('GET', VIAGEM, '/transactions')).at(-1)!
    expect([...new URLSearchParams(viagemCall.path.split('?')[1]).keys()]).toEqual(['page', 'size'])

    await switchTo(user, 'Casa')
    await screen.findByText('Almoço da Casa')
    expect(screen.getByRole('button', { name: /^Filtros/ })).toHaveTextContent(/^Filtros$/)
    const casaCall = api.callsTo(wsRoute('GET', CASA, '/transactions')).at(-1)!
    expect([...new URLSearchParams(casaCall.path.split('?')[1]).keys()]).toEqual(['page', 'size'])
  })

  it('a resposta atrasada do workspace anterior chega depois e não aparece', async () => {
    let release!: () => void
    const wait = new Promise<void>((resolve) => {
      release = resolve
    })
    const { api, user } = openApp()
    api.on(wsRoute('GET', CASA, '/transactions'), async () => (await wait, ok({ items: [lunch], page: 1, size: 20, totalItems: 1, totalPages: 1 })))
    // a lista da Casa ainda carrega: a pessoa vai ao perfil, troca de workspace e abre os lançamentos do outro
    await user.click(await screen.findByRole('link', { name: /Ana Maria/ }))
    await user.click(await screen.findByRole('button', { name: 'Usar Viagem' }))
    await user.click(screen.getByRole('link', { name: 'Lançamentos' }))
    await screen.findByText('Passagem da Viagem')

    await act(async () => release())

    expect(screen.getByText('Passagem da Viagem')).toBeInTheDocument()
    expect(screen.queryByText('Almoço da Casa')).toBeNull()
  })

  it('um endereço de lançamento do outro workspace é "não encontrado": a consulta vai ao workspace atual, igual a um inexistente', async () => {
    const { api } = openApp(['/lancamentos/tx-viagem'])

    expect(await screen.findByText(FAILURE_MESSAGES.notFound)).toBeInTheDocument()
    expect(api.callsTo(wsRoute('GET', CASA, '/transactions/tx-viagem'))).toHaveLength(1)
    expect(api.callsTo(wsRoute('GET', VIAGEM, '/transactions/tx-viagem'))).toHaveLength(0)
    expect(screen.queryByText('Passagem da Viagem')).toBeNull()
  })
})

describe('a sessão que termina no meio do uso', () => {
  it('um 401 ao carregar a lista leva à entrada com o aviso de sessão terminada', async () => {
    const { api } = openApp()
    api.on(wsRoute('GET', CASA, '/transactions'), problem(401, 'Não autenticado'))

    expect(await screen.findByText('Sua sessão terminou. Entre de novo.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/entrar')
  })

  it('um 401 ao registrar também', async () => {
    const { api, user } = openApp(['/lancamentos/novo'])
    api.on(wsRoute('POST', CASA, '/transactions'), problem(401, 'Não autenticado'))
    await screen.findByLabelText('Valor')
    await user.type(screen.getByLabelText('Valor'), '10')
    await user.type(screen.getByLabelText('Descrição'), 'x')
    await user.selectOptions(screen.getByLabelText('Conta'), 'a-casa')
    await user.click(screen.getByRole('radio', { name: /Mercado/ }))

    await user.click(screen.getByRole('button', { name: 'Registrar' }))

    expect(await screen.findByText('Sua sessão terminou. Entre de novo.')).toBeInTheDocument()
  })
})

describe('as recusas de permissão da API (o papel mudou em outro aparelho)', () => {
  it('registrar, salvar, efetivar e excluir mostram a mensagem de permissão e nada muda', async () => {
    const pending = makeTransaction({ id: 'tx-p', accountId: 'a-casa', categoryId: 'c-mercado', amountCents: -100, description: 'A pagar', status: 'PENDING' })
    const { api, casa, user } = openApp(['/lancamentos/tx-p'])
    casa.store.push(pending)
    const forbidden = problem(403, 'Proibido')
    api.onMatch('PUT', /\/transactions\/tx-p$/, () => forbidden)
    api.onMatch('POST', /\/transactions\/tx-p\/clear$/, () => forbidden)
    api.onMatch('DELETE', /\/transactions\/tx-p$/, () => forbidden)
    await screen.findByRole('heading', { name: 'Editar lançamento' })
    const message = 'Você não tem permissão para alterar isto neste workspace.'

    await user.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(message)

    const shown = () => screen.queryAllByRole('alert').some((alert) => alert.textContent?.includes(message))
    await user.click(screen.getByRole('button', { name: 'Efetivar' }))
    await waitFor(() => expect(shown()).toBe(true))

    await user.click(screen.getByRole('button', { name: 'Excluir' }))
    await user.click(within(screen.getByRole('dialog', { name: 'Excluir este lançamento?' })).getByRole('button', { name: 'Excluir' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Excluir este lançamento?' })).toBeNull()) // a falha fecha o diálogo
    expect(shown()).toBe(true)

    expect(casa.store.find((t) => t.id === 'tx-p')).toMatchObject({ status: 'PENDING' })
    expect(screen.getByRole('heading', { name: 'Editar lançamento' })).toBeInTheDocument()
    expect(screen.queryByText('Lançamento excluído.')).toBeNull()
    expect(screen.queryByText('Lançamento efetivado.')).toBeNull()
    expect(screen.queryByText('Lançamento salvo.')).toBeNull()
  })
})

describe('acessibilidade', () => {
  it('a visão do leitor passa no axe', async () => {
    localStorage.setItem('tostao.workspace.u-ana', VIAGEM)
    const { container, user } = openApp(['/lancamentos'])
    await screen.findByText('Passagem da Viagem')
    await user.click(screen.getByRole('button', { name: /Passagem da Viagem/ }))
    await screen.findByRole('heading', { name: 'Lançamento' })

    await expectNoSeriousViolations(container)
  })
})
