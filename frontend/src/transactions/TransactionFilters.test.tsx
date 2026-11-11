import { fireEvent, screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Account, CategoryTree, Transaction } from '../api/types'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, ok, wsRoute } from '../test/fakeApi'
import { installTransactions } from '../test/fakeTransactions'
import { WID, makeAccount, makeCategory, makeCategoryTree, makeMe, makeTransaction } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const carteira = makeAccount({ id: 'a-carteira', name: 'Carteira' })
const reserva = makeAccount({ id: 'a-reserva', name: 'Reserva' })
const accounts: Account[] = [carteira, reserva]
const mercado = makeCategory({ id: 'c-mercado', name: 'Mercado' })
const alimentacao: CategoryTree = makeCategoryTree({ id: 'c-alim', name: 'Alimentação', kind: 'EXPENSE' }, [mercado])
const salario = makeCategoryTree({ id: 'c-sal', name: 'Salário', kind: 'INCOME' })
const categories = [alimentacao, salario]

const tx = (extra: Partial<Transaction>) => makeTransaction({ accountId: 'a-carteira', categoryId: 'c-mercado', amountCents: -1000, occurredOn: '2026-11-10', ...extra })
const data: Transaction[] = [
  tx({ description: 'Mercado do Zé', occurredOn: '2026-11-10' }),
  tx({ description: 'Almoço', categoryId: 'c-alim', occurredOn: '2026-11-05', status: 'PENDING' }),
  tx({ description: 'Salário', type: 'INCOME', categoryId: 'c-sal', accountId: 'a-reserva', amountCents: 500000, occurredOn: '2026-10-28' }),
  tx({ description: 'Desconto de 50% na loja', categoryId: 'c-alim', accountId: 'a-reserva', occurredOn: '2026-09-15' }),
]

function open(initial: Transaction[] = data) {
  const api = fakeApi({
    'GET /api/v1/me': ok(makeMe('OWNER')),
    [wsRoute('GET', WID, '/accounts')]: ok(accounts),
    [wsRoute('GET', WID, '/categories')]: ok(categories),
  })
  installTransactions(api, initial, { workspaceId: WID, categories })
  return { api, ...renderApp(['/lancamentos']) }
}
const LIST = wsRoute('GET', WID, '/transactions')
const params = (api: ReturnType<typeof open>['api']) => {
  const calls = api.callsTo(LIST)
  return new URLSearchParams(calls[calls.length - 1].path.split('?')[1])
}
const toggle = () => screen.getByRole('button', { name: /^Filtros/ })
const openPanel = async (user: ReturnType<typeof open>['user']) => {
  await screen.findByText(/^\d+ lançamentos?$/)
  await user.click(toggle())
  return screen.findByRole('form', { name: 'Filtros' })
}
const choose = (user: ReturnType<typeof open>['user'], label: string, value: string) => user.selectOptions(screen.getByLabelText(label), value)
const apply = (user: ReturnType<typeof open>['user']) => user.click(screen.getByRole('button', { name: 'Aplicar filtros' }))
const countRows = (container: HTMLElement) => container.querySelectorAll('.transaction-list > li').length

describe('o painel de filtros', () => {
  it('abre e fecha pelo botão "Filtros", que informa o estado', async () => {
    const { user } = open()
    await screen.findByText('4 lançamentos')
    expect(toggle()).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('form', { name: 'Filtros' })).toBeNull()

    await user.click(toggle())
    expect(toggle()).toHaveAttribute('aria-expanded', 'true')
    expect(await screen.findByRole('form', { name: 'Filtros' })).toBeInTheDocument()

    await user.click(toggle())
    expect(screen.queryByRole('form', { name: 'Filtros' })).toBeNull()
  })

  it('traz período, conta, categoria, tipo, estado e busca', async () => {
    const { user } = open()
    await openPanel(user)

    for (const label of ['De', 'Até', 'Conta', 'Categoria', 'Tipo', 'Estado', 'Buscar na descrição']) expect(screen.getByLabelText(label)).toBeInTheDocument()
    expect(screen.getByLabelText('Conta')).toHaveValue('')
  })
})

describe('cada filtro chega à API pelo parâmetro do contrato', () => {
  it('conta', async () => {
    const { api, container, user } = open()
    await openPanel(user)

    await choose(user, 'Conta', 'a-reserva')
    await apply(user)

    await screen.findByText('2 lançamentos')
    expect(params(api).get('accountId')).toBe('a-reserva')
    expect(countRows(container)).toBe(2)
  })

  it('período (de e até, inclusivos)', async () => {
    const { api, container, user } = open()
    await openPanel(user)

    fireEvent.change(screen.getByLabelText('De'), { target: { value: '2026-11-05' } })
    fireEvent.change(screen.getByLabelText('Até'), { target: { value: '2026-11-10' } })
    await apply(user)

    await screen.findByText('2 lançamentos')
    expect(params(api).get('from')).toBe('2026-11-05')
    expect(params(api).get('to')).toBe('2026-11-10')
    expect(countRows(container)).toBe(2)
  })

  it('categoria principal inclui as subcategorias; tipo; estado', async () => {
    const { api, user } = open()
    await openPanel(user)
    await choose(user, 'Categoria', 'c-alim')
    await apply(user)
    await screen.findByText('3 lançamentos') // Mercado do Zé (sub), Almoço e Desconto (principal)
    expect(params(api).get('categoryId')).toBe('c-alim')

    await user.click(toggle())
    await choose(user, 'Categoria', '')
    await choose(user, 'Tipo', 'INCOME')
    await apply(user)
    await screen.findByText('1 lançamento')
    expect(params(api).get('type')).toBe('INCOME')

    await user.click(toggle())
    await choose(user, 'Tipo', '')
    await choose(user, 'Estado', 'PENDING')
    await apply(user)
    await screen.findByText('1 lançamento')
    expect(params(api).get('status')).toBe('PENDING')
  })

  it('a busca por texto, com % e _ valendo literalmente', async () => {
    const { api, user } = open()
    await openPanel(user)

    await user.type(screen.getByLabelText('Buscar na descrição'), '50%')
    await apply(user)

    expect(await screen.findByRole('button', { name: /Desconto de 50% na loja/ })).toBeInTheDocument()
    expect(params(api).get('q')).toBe('50%')
    expect(screen.getByText('1 lançamento')).toBeInTheDocument()
  })

  it('a combinação deles, e só os preenchidos vão', async () => {
    const { api, user } = open()
    await openPanel(user)

    await choose(user, 'Conta', 'a-carteira')
    await choose(user, 'Estado', 'PENDING')
    fireEvent.change(screen.getByLabelText('De'), { target: { value: '2026-11-01' } })
    await apply(user)

    await screen.findByText('1 lançamento')
    expect([...params(api).keys()]).toEqual(['page', 'size', 'from', 'accountId', 'status'])
  })
})

describe('aplicar e limpar', () => {
  it('mostra a contagem em "Filtros (N)" e as etiquetas dos filtros ativos, e fecha o painel devolvendo o foco', async () => {
    const { user } = open()
    await openPanel(user)

    await choose(user, 'Conta', 'a-carteira')
    await choose(user, 'Tipo', 'EXPENSE')
    await apply(user)

    await screen.findByText('2 lançamentos')
    expect(toggle()).toHaveTextContent('Filtros (2)')
    expect(screen.getByRole('list', { name: 'Filtros ativos' })).toHaveTextContent('Conta: Carteira')
    expect(screen.getByRole('list', { name: 'Filtros ativos' })).toHaveTextContent('Tipo: Despesa')
    expect(screen.queryByRole('form', { name: 'Filtros' })).toBeNull()
    expect(toggle()).toHaveFocus()
  })

  it('aplicar volta à primeira página', async () => {
    const many = Array.from({ length: 45 }, (_, i) => tx({ id: `m${i}`, description: `Item ${i}`, occurredOn: '2026-11-10', createdAt: `2026-01-01T00:00:${String(i).padStart(2, '0')}.000Z` }))
    const { api, user } = open(many)
    await screen.findByText('45 lançamentos')
    await user.click(screen.getByRole('button', { name: 'Próxima' }))
    await screen.findByText('Página 2 de 3')

    await user.click(toggle())
    await choose(user, 'Tipo', 'EXPENSE')
    await apply(user)

    await screen.findByText('Página 1 de 3')
    expect(params(api).get('page')).toBe('1')
  })

  it('"Limpar filtros" tira todos, fecha o painel e devolve a lista inteira', async () => {
    const { api, user } = open()
    await openPanel(user)
    await choose(user, 'Conta', 'a-reserva')
    await apply(user)
    await screen.findByText('2 lançamentos')

    await user.click(toggle())
    await user.click(screen.getByRole('button', { name: 'Limpar filtros' }))

    await screen.findByText('4 lançamentos')
    expect(toggle()).toHaveTextContent(/^Filtros$/)
    expect(screen.queryByRole('list', { name: 'Filtros ativos' })).toBeNull()
    expect([...params(api).keys()]).toEqual(['page', 'size'])
  })

  it('o painel reabre com os filtros aplicados', async () => {
    const { user } = open()
    await openPanel(user)
    await choose(user, 'Conta', 'a-reserva')
    await user.type(screen.getByLabelText('Buscar na descrição'), 'salário')
    await apply(user)
    await screen.findByText('1 lançamento')

    await user.click(toggle())

    expect(screen.getByLabelText('Conta')).toHaveValue('a-reserva')
    expect(screen.getByLabelText('Buscar na descrição')).toHaveValue('salário')
  })

  it('sem nenhum resultado: "Nenhum lançamento encontrado" com "Limpar filtros", que restaura a lista', async () => {
    const { user } = open()
    await openPanel(user)
    await user.type(screen.getByLabelText('Buscar na descrição'), 'nada disso existe')
    await apply(user)

    expect(await screen.findByText('Nenhum lançamento encontrado')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Limpar filtros' }))

    expect(await screen.findByText('4 lançamentos')).toBeInTheDocument()
  })

  it('os filtros sobrevivem a ir a outra tela e voltar à lista', async () => {
    const { user } = open()
    await openPanel(user)
    await choose(user, 'Conta', 'a-reserva')
    await apply(user)
    await screen.findByText('2 lançamentos')

    await user.click(screen.getByRole('link', { name: 'Categorias' }))
    await screen.findByRole('heading', { name: 'Categorias' })
    await user.click(screen.getByRole('link', { name: 'Lançamentos' }))

    expect(await screen.findByText('2 lançamentos')).toBeInTheDocument()
    expect(toggle()).toHaveTextContent('Filtros (1)')
  })
})

describe('as recusas no próprio painel', () => {
  it('a data final antes da inicial: erro no campo da final, nada é consultado e o foco vai a ele', async () => {
    const { api, user } = open()
    await openPanel(user)
    const calls = api.callsTo(LIST).length

    fireEvent.change(screen.getByLabelText('De'), { target: { value: '2026-11-10' } })
    fireEvent.change(screen.getByLabelText('Até'), { target: { value: '2026-11-01' } })
    await apply(user)

    expect(screen.getByLabelText('Até')).toHaveAccessibleDescription('A data final não pode ser anterior à inicial.')
    expect(screen.getByLabelText('Até')).toHaveFocus()
    expect(api.callsTo(LIST)).toHaveLength(calls)
    expect(screen.getByRole('form', { name: 'Filtros' })).toBeInTheDocument()
  })

  it('a busca de 101 caracteres é recusada no campo', async () => {
    const { api, user } = open()
    await openPanel(user)
    const calls = api.callsTo(LIST).length

    await user.click(screen.getByLabelText('Buscar na descrição'))
    await user.paste('a'.repeat(101))
    await apply(user)

    expect(screen.getByLabelText('Buscar na descrição')).toHaveAccessibleDescription('Use no máximo 100 caracteres.')
    expect(api.callsTo(LIST)).toHaveLength(calls)
  })

  it('corrigir o erro e aplicar de novo funciona', async () => {
    const { user } = open()
    await openPanel(user)
    fireEvent.change(screen.getByLabelText('De'), { target: { value: '2026-11-10' } })
    fireEvent.change(screen.getByLabelText('Até'), { target: { value: '2026-11-01' } })
    await apply(user)

    fireEvent.change(screen.getByLabelText('Até'), { target: { value: '2026-11-30' } })
    await apply(user)

    await waitFor(() => expect(screen.queryByRole('form', { name: 'Filtros' })).toBeNull())
    expect(await screen.findByText('1 lançamento')).toBeInTheDocument()
  })
})

describe('acessibilidade', () => {
  it('o painel aberto, com erros e com os filtros ativos passa no axe', async () => {
    const { container, user } = open()
    await openPanel(user)
    await expectNoSeriousViolations(container)

    fireEvent.change(screen.getByLabelText('De'), { target: { value: '2026-11-10' } })
    fireEvent.change(screen.getByLabelText('Até'), { target: { value: '2026-11-01' } })
    await apply(user)
    await expectNoSeriousViolations(container)

    fireEvent.change(screen.getByLabelText('Até'), { target: { value: '2026-11-30' } })
    await apply(user)
    await screen.findByText('1 lançamento')
    await expectNoSeriousViolations(container)
  })
})
