import { act, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Account, CategoryTree, Transaction } from '../api/types'
import { previousDay, todayISO } from '../dates/dates'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, ok, wsRoute } from '../test/fakeApi'
import { installTransactions } from '../test/fakeTransactions'
import { WID, makeAccount, makeCategory, makeCategoryTree, makeMe, makeTransaction } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const TODAY = todayISO()
const YESTERDAY = previousDay(TODAY)
const OLD = '2019-03-05'

const carteira = makeAccount({ id: 'a-carteira', name: 'Carteira' })
const reserva = makeAccount({ id: 'a-reserva', name: 'Reserva' })
const antiga = makeAccount({ id: 'a-antiga', name: 'Antiga', archived: true })
const accounts: Account[] = [carteira, reserva, antiga]
const mercado = makeCategory({ id: 'c-mercado', name: 'Mercado' })
const alimentacao: CategoryTree = makeCategoryTree({ id: 'c-alim', name: 'Alimentação', kind: 'EXPENSE' }, [mercado])
const salario = makeCategoryTree({ id: 'c-sal', name: 'Salário', kind: 'INCOME' })
const velha = makeCategoryTree({ id: 'c-velha', name: 'Velha', kind: 'EXPENSE', archived: true })
const categories = [alimentacao, salario, velha]

const expense = (extra: Partial<Transaction> = {}) => makeTransaction({ accountId: 'a-carteira', categoryId: 'c-mercado', amountCents: -3590, description: 'Almoço', occurredOn: TODAY, ...extra })

function open(initial: Transaction[], { role = 'OWNER' as 'OWNER' | 'EDITOR' | 'VIEWER', entry = ['/lancamentos'] as Parameters<typeof renderApp>[0] } = {}) {
  const api = fakeApi({
    'GET /api/v1/me': ok(makeMe(role)),
    [wsRoute('GET', WID, '/accounts')]: ok(accounts),
    [wsRoute('GET', WID, '/categories')]: ok(categories),
  })
  const memory = installTransactions(api, initial, { workspaceId: WID, categories })
  return { api, memory, ...renderApp(entry) }
}
const LIST = wsRoute('GET', WID, '/transactions')
const rows = (container: HTMLElement) => container.querySelectorAll('.transaction-list > li')
const ready = () => screen.findByText(/\d+ lançamentos?$/)

describe('a lista', () => {
  it('mostra os lançamentos do mais recente ao mais antigo, agrupados por dia, com Hoje e Ontem', async () => {
    open([
      expense({ description: 'Antigo', occurredOn: OLD }),
      expense({ description: 'De ontem', occurredOn: YESTERDAY }),
      expense({ description: 'De hoje', occurredOn: TODAY }),
    ])
    await ready()

    const titles = screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)
    expect(titles).toEqual(['Hoje', 'Ontem', '5 de mar. de 2019'])
    expect(within(screen.getByRole('region', { name: 'Hoje' })).getByText('De hoje')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Ontem' })).getByText('De ontem')).toBeInTheDocument()
    expect(screen.getByText('3 lançamentos')).toBeInTheDocument()
  })

  it('cada linha traz descrição, conta, categoria e o valor com o sinal do tipo', async () => {
    open([
      expense({ description: 'Almoço' }),
      makeTransaction({ type: 'INCOME', accountId: 'a-reserva', categoryId: 'c-sal', amountCents: 500000, description: 'Salário de novembro', occurredOn: TODAY }),
    ])
    await ready()

    const lunch = screen.getByRole('button', { name: /Almoço/ })
    expect(lunch).toHaveTextContent('Carteira · Alimentação › Mercado')
    expect(lunch).toHaveTextContent(/−R\$\s*35,90/)
    const pay = screen.getByRole('button', { name: /Salário de novembro/ })
    expect(pay).toHaveTextContent('Reserva · Salário')
    expect(pay).toHaveTextContent(/\+R\$\s*5\.000,00/)
  })

  it('as duas pontas de uma transferência aparecem, cada uma na sua conta, com a direção', async () => {
    const out = makeTransaction({ type: 'TRANSFER', description: 'Para a reserva', accountId: 'a-carteira', counterpartAccountId: 'a-reserva', amountCents: -10000, transferGroupId: 'g', occurredOn: TODAY })
    const into = makeTransaction({ type: 'TRANSFER', description: 'Para a reserva', accountId: 'a-reserva', counterpartAccountId: 'a-carteira', amountCents: 10000, transferGroupId: 'g', occurredOn: TODAY })
    open([out, into])
    await ready()

    const buttons = screen.getAllByRole('button', { name: /Para a reserva/ })
    expect(buttons).toHaveLength(2)
    expect(buttons.some((b) => /Carteira · Para Reserva/.test(b.textContent ?? ''))).toBe(true)
    expect(buttons.some((b) => /Reserva · De Carteira/.test(b.textContent ?? ''))).toBe(true)
    for (const button of buttons) expect(button).toHaveTextContent(/R\$\s*100,00/)
  })

  it('marca o pendente e mostra os selos de parcela, recorrência e importação', async () => {
    open([
      expense({ description: 'A pagar', status: 'PENDING' }),
      expense({ description: 'Parcelada', installmentNumber: 2, installmentCount: 10 }),
      expense({ description: 'Mensal', recurrenceId: 'r1', occurrenceNumber: 4 }),
      expense({ description: 'Do extrato', imported: true }),
    ])
    await ready()

    expect(screen.getByRole('button', { name: /A pagar/ })).toHaveTextContent('Pendente')
    expect(screen.getByRole('button', { name: /Parcelada/ })).toHaveTextContent('Parcela 2 de 10')
    expect(screen.getByRole('button', { name: /Mensal/ })).toHaveTextContent('Recorrente')
    expect(screen.getByRole('button', { name: /Do extrato/ })).toHaveTextContent('Importado')
  })

  it('o nome de uma conta e de uma categoria arquivadas continua aparecendo; sem categoria aparece como tal', async () => {
    open([
      expense({ description: 'Em conta antiga', accountId: 'a-antiga', categoryId: 'c-velha' }),
      expense({ description: 'Sem nada', categoryId: null }),
      expense({ description: 'Conta apagada', accountId: 'sumiu' }),
    ])
    await ready()

    expect(screen.getByRole('button', { name: /Em conta antiga/ })).toHaveTextContent('Antiga (arquivada) · Velha (arquivada)')
    expect(screen.getByRole('button', { name: /Sem nada/ })).toHaveTextContent('Sem categoria')
    expect(screen.getByRole('button', { name: /Conta apagada/ })).toHaveTextContent('Conta removida')
  })

  it('uma linha leva ao lançamento, num endereço próprio', async () => {
    const tx = expense({ id: 'tx-abre' })
    const { user } = open([tx])
    await ready()

    await user.click(screen.getByRole('button', { name: /Almoço/ }))

    expect(screen.getByTestId('location')).toHaveTextContent('/lancamentos/tx-abre')
  })

  it('o aviso de resultado vem do estado do roteador', async () => {
    open([expense()], { entry: [{ pathname: '/lancamentos', state: { notice: 'Lançamento registrado.' } }] })

    expect(await screen.findByText('Lançamento registrado.')).toBeInTheDocument()
  })

  it('"Lançamentos" está na navegação e é o item atual', async () => {
    open([expense()])
    await ready()

    expect(screen.getByRole('link', { name: 'Lançamentos' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Contas' })).not.toHaveAttribute('aria-current')
  })
})

describe('a paginação', () => {
  const many = Array.from({ length: 45 }, (_, i) =>
    expense({ id: `m${i}`, description: `Item ${i}`, occurredOn: `2019-05-${String(28 - (i % 28)).padStart(2, '0')}`, createdAt: `2026-01-01T00:00:${String(i).padStart(2, '0')}.000Z` }),
  )

  it('mostra 20 por página, o total, e leva à próxima e à anterior com os botões nos extremos indisponíveis', async () => {
    const { api, container, user } = open(many)
    await screen.findByText('45 lançamentos')
    expect(rows(container)).toHaveLength(20)
    expect(screen.getByText('Página 1 de 3')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Anterior' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Próxima' }))
    expect(await screen.findByText('Página 2 de 3')).toBeInTheDocument()
    expect(rows(container)).toHaveLength(20)

    await user.click(screen.getByRole('button', { name: 'Próxima' }))
    expect(await screen.findByText('Página 3 de 3')).toBeInTheDocument()
    expect(rows(container)).toHaveLength(5)
    expect(screen.getByRole('button', { name: 'Próxima' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Anterior' }))
    expect(await screen.findByText('Página 2 de 3')).toBeInTheDocument()
    expect(api.callsTo(LIST).map((c) => new URLSearchParams(c.path.split('?')[1]).get('page'))).toEqual(['1', '2', '3', '2'])
  })

  it('ao trocar de página o foco vai ao título da lista', async () => {
    const { user } = open(many)
    await screen.findByText('45 lançamentos')

    await user.click(screen.getByRole('button', { name: 'Próxima' }))
    await screen.findByText('Página 2 de 3')

    expect(screen.getByRole('heading', { name: 'Lista de lançamentos' })).toHaveFocus()
  })

  it('uma lista de uma página só não tem controles de página', async () => {
    open(many.slice(0, 5))
    await screen.findByText('5 lançamentos')

    expect(screen.queryByRole('navigation', { name: 'Páginas de lançamentos' })).toBeNull()
  })

  it('uma página além do fim (a lista encolheu em outro aparelho) cai na última que existe', async () => {
    const { api, container, user } = open(many.slice(0, 25))
    await screen.findByText('25 lançamentos')
    // na página 2 a API agora diz que só existe 1 página (outro aparelho excluiu itens)
    const real = api.callsTo(LIST).length
    expect(real).toBe(1)
    api.on(LIST, (call) => {
      const page = Number(new URLSearchParams(call.path.split('?')[1]).get('page'))
      return page === 1
        ? ok({ items: many.slice(0, 5), page: 1, size: 20, totalItems: 5, totalPages: 1 })
        : ok({ items: [], page, size: 20, totalItems: 5, totalPages: 1 })
    })

    await user.click(screen.getByRole('button', { name: 'Próxima' }))

    expect(await screen.findByText('5 lançamentos')).toBeInTheDocument()
    expect(rows(container)).toHaveLength(5)
    expect(screen.queryByText(/Página/)).toBeNull()
  })
})

describe('os estados da tela', () => {
  it('mostra "Carregando lançamentos…" enquanto consulta', async () => {
    let release!: () => void
    const wait = new Promise<void>((resolve) => {
      release = resolve
    })
    const { api } = open([expense()])
    api.on(LIST, async () => (await wait, ok({ items: [expense()], page: 1, size: 20, totalItems: 1, totalPages: 1 })))

    expect(await screen.findByText('Carregando lançamentos…')).toBeInTheDocument()
    release()
    await screen.findByText('1 lançamento')
    expect(screen.queryByText('Carregando lançamentos…')).toBeNull()
  })

  it('sem lançamentos, explica o que fazer, e o dono vê "Novo lançamento"', async () => {
    open([])

    expect(await screen.findByText('Nenhum lançamento ainda')).toBeInTheDocument()
    expect(screen.getByText(/Registre o primeiro lançamento/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Novo lançamento' })).toHaveAttribute('href', '/lancamentos/novo')
  })

  it('sem lançamentos, o leitor vê a explicação e nenhuma ação', async () => {
    open([], { role: 'VIEWER' })

    expect(await screen.findByText('Nenhum lançamento ainda')).toBeInTheDocument()
    expect(screen.getByText(/Quem pode editar este workspace registra os lançamentos/)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Novo lançamento' })).toBeNull()
  })

  it('o erro ao carregar a lista mostra o aviso com "Tentar de novo", que consulta outra vez', async () => {
    const { api, user } = open([expense()])
    api.on(LIST, networkError())
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar os lançamentos. Tente de novo.')

    api.on(LIST, ok({ items: [expense()], page: 1, size: 20, totalItems: 1, totalPages: 1 }))
    await user.click(screen.getByRole('button', { name: 'Tentar de novo' }))

    expect(await screen.findByText('1 lançamento')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('o erro ao carregar as contas (os nomes) também é erro: a lista nunca aparece com identificadores', async () => {
    const { api, user } = open([expense()])
    api.on(wsRoute('GET', WID, '/accounts'), networkError())

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar os lançamentos.')
    expect(screen.queryByRole('button', { name: /Almoço/ })).toBeNull()

    api.on(wsRoute('GET', WID, '/accounts'), ok(accounts))
    await user.click(screen.getByRole('button', { name: 'Tentar de novo' }))
    expect(await screen.findByRole('button', { name: /Almoço/ })).toBeInTheDocument()
  })

  it('o dono e o editor veem "Novo lançamento"; o leitor, não, mas abre as linhas', async () => {
    for (const role of ['OWNER', 'EDITOR'] as const) {
      const view = open([expense()], { role })
      await ready()
      expect(screen.getByRole('link', { name: 'Novo lançamento' })).toBeInTheDocument()
      view.unmount()
    }
    open([expense()], { role: 'VIEWER' })
    await ready()
    expect(screen.queryByRole('link', { name: 'Novo lançamento' })).toBeNull()
    expect(screen.getByRole('button', { name: /Almoço/ })).toBeEnabled()
  })

  it('passa no axe: com a lista, vazia e com erro', async () => {
    const full = open([expense(), expense({ status: 'PENDING', description: 'Pendente' })])
    await ready()
    await expectNoSeriousViolations(full.container)
    full.unmount()

    const empty = open([])
    await screen.findByText('Nenhum lançamento ainda')
    await expectNoSeriousViolations(empty.container)
    empty.unmount()

    const failing = open([expense()])
    failing.api.on(LIST, networkError())
    await screen.findByRole('alert')
    await act(async () => {})
    await expectNoSeriousViolations(failing.container)
  })
})
