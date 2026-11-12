import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Account } from '../api/types'
import { formatBRL } from '../design-system/src'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, ok, problem, wsRoute } from '../test/fakeApi'
import { WID, makeAccount, makeBalanceReport, makeMe } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

// Os centavos de valores grandes ficam num span à parte; o espaço do real é não separável.
const text = (value: string) => value.replace(/\s+/g, ' ')

// O Testing Library normaliza o espaço não separável do formatBRL em espaço comum.
const brl = (cents: number) => formatBRL(cents).replace(/ /g, ' ')

const carteira = makeAccount({ name: 'Carteira', type: 'CASH', balanceCents: 15000 })
const nubank = makeAccount({ name: 'Nubank', type: 'CREDIT_CARD', balanceCents: -50000, creditLimitCents: 500000, closingDay: 10, dueDay: 17 })
const reserva = makeAccount({ name: 'Reserva', type: 'SAVINGS', balanceCents: 100000 })
const antiga = makeAccount({ name: 'Conta antiga', type: 'CHECKING', balanceCents: 999, archived: true })

function open(accounts: Account[], role: 'OWNER' | 'EDITOR' | 'VIEWER' = 'OWNER', entry: Parameters<typeof renderApp>[0] = ['/contas']) {
  const api = fakeApi({
    'GET /api/v1/me': ok(makeMe(role)),
    [wsRoute('GET', WID, '/accounts')]: (call) => ok(call.path.includes('includeArchived=true') ? accounts : accounts.filter((a) => !a.archived)),
    [wsRoute('GET', WID, '/reports/balance')]: ok(makeBalanceReport(accounts)),
  })
  return { api, ...renderApp(entry) }
}
const stat = () => screen.getByText('Saldo total').closest('.tx-stat') as HTMLElement
const rows = () => within(screen.getByRole('list', { name: 'Contas' })).getAllByRole('listitem')

describe('a lista de contas', () => {
  it('mostra o saldo total e uma linha por conta ativa, com nome, tipo em português e saldo', async () => {
    open([carteira, nubank, reserva])

    await screen.findByRole('list', { name: 'Contas' })
    expect(rows()).toHaveLength(3)
    expect(stat()).toHaveTextContent(text(brl(65000)))
    expect(rows()[0]).toHaveTextContent(text('Carteira'))
    expect(rows()[0]).toHaveTextContent(text('Dinheiro'))
    expect(rows()[0]).toHaveTextContent(text(brl(15000)))
    expect(within(rows()[2]).getByText('Poupança')).toBeInTheDocument()
  })

  it('usa só o workspace atual nas chamadas, e o total é o do relatório da API', async () => {
    const { api } = open([carteira, reserva])

    await screen.findByRole('list', { name: 'Contas' })

    expect(api.callsTo(wsRoute('GET', WID, '/accounts'))).toHaveLength(1)
    expect(api.callsTo(wsRoute('GET', WID, '/reports/balance'))).toHaveLength(1)
    expect(api.callsTo(wsRoute('GET', WID, '/accounts'))[0].path).toBe(`/api/v1/workspaces/${WID}/accounts`)
    expect(stat()).toHaveTextContent(text(brl(115000)))
  })

  it('o cartão de crédito mostra fechamento, vencimento e limite; as outras contas não têm linha de apoio', async () => {
    open([carteira, nubank])

    await screen.findByRole('list', { name: 'Contas' })

    expect(screen.getByText(`Fecha dia 10 · vence dia 17 · limite ${brl(500000)}`)).toBeInTheDocument()
    expect(within(rows()[0]).queryByText(/Fecha dia|limite/)).toBeNull()
  })

  it('um cartão sem dados informados não inventa linha de apoio', async () => {
    open([makeAccount({ name: 'Cartão simples', type: 'CREDIT_CARD', balanceCents: 0 })])

    await screen.findByRole('list', { name: 'Contas' })

    expect(screen.queryByText(/Fecha dia|vence dia|limite/)).toBeNull()
  })

  it('o saldo negativo mostra o sinal e é anunciado como negativo', async () => {
    open([nubank])

    await screen.findByRole('list', { name: 'Contas' })

    expect(rows()[0]).toHaveTextContent(/Saldo negativo/)
    expect(rows()[0]).toHaveTextContent(text(`−${brl(50000)}`))
    expect(stat()).toHaveTextContent(text(`−${brl(50000)}`))
  })

  it('um tipo desconhecido da API aparece como "Conta", sem quebrar a lista', async () => {
    open([makeAccount({ name: 'Estranha', type: 'CRIPTO' as Account['type'], balanceCents: 1 }), carteira])

    await screen.findByRole('list', { name: 'Contas' })

    expect(rows()[0]).toHaveTextContent(text('Conta'))
    expect(rows()).toHaveLength(2)
  })

  it('uma lista grande (300 contas) aparece inteira', async () => {
    const many = Array.from({ length: 300 }, (_, i) => makeAccount({ name: `Conta ${i}`, balanceCents: 100 }))
    const { container } = open(many)

    await screen.findByText('Saldo total')

    // (contado direto no DOM: as consultas por papel do Testing Library são lentas em listas grandes no jsdom)
    expect(container.querySelectorAll('ul[aria-label="Contas"] > li')).toHaveLength(300)
    expect(stat()).toHaveTextContent(text(brl(30000)))
  })
})

describe('as contas arquivadas', () => {
  it('só aparecem com "Mostrar arquivadas", marcadas e fora do total; ao desligar, somem de novo', async () => {
    const { api, user } = open([carteira, antiga])
    await screen.findByRole('list', { name: 'Contas' })
    expect(screen.queryByText('Conta antiga')).toBeNull()

    await user.click(screen.getByRole('checkbox', { name: 'Mostrar arquivadas' }))

    expect(await screen.findByText('Conta antiga')).toBeInTheDocument()
    expect(screen.getByText(/Conta corrente · arquivada/)).toBeInTheDocument()
    expect(stat()).toHaveTextContent(text(brl(15000))) // o total segue só com as ativas
    expect(screen.getByText('Considera só as contas ativas.')).toBeInTheDocument()
    expect(api.callsTo(wsRoute('GET', WID, '/accounts')).map((c) => c.path.includes('includeArchived=true'))).toEqual([false, true])

    await user.click(screen.getByRole('checkbox', { name: 'Mostrar arquivadas' }))

    await screen.findByRole('list', { name: 'Contas' })
    expect(screen.queryByText('Conta antiga')).toBeNull()
    expect(screen.queryByText('Considera só as contas ativas.')).toBeNull()
  })
})

describe('os estados da tela', () => {
  it('mostra "Carregando contas…" enquanto consulta', async () => {
    let release!: () => void
    const wait = new Promise<void>((resolve) => {
      release = resolve
    })
    const { api } = open([carteira])
    api.on(wsRoute('GET', WID, '/accounts'), async () => (await wait, ok([carteira])))

    expect(await screen.findByText('Carregando contas…')).toBeInTheDocument()
    release()
    await screen.findByRole('list', { name: 'Contas' })
    expect(screen.queryByText('Carregando contas…')).toBeNull()
  })

  it('sem contas, explica o que fazer, e o dono vê "Criar conta" (leva a /contas/nova)', async () => {
    open([])

    expect(await screen.findByText('Nenhuma conta ainda')).toBeInTheDocument()
    expect(screen.getByText(/Crie a primeira conta/)).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Contas' })).toBeNull()
    expect(screen.getByRole('link', { name: 'Criar conta' })).toHaveAttribute('href', '/contas/nova')
  })

  it('sem contas, o leitor vê a explicação e nenhuma ação', async () => {
    open([], 'VIEWER')

    expect(await screen.findByText('Nenhuma conta ainda')).toBeInTheDocument()
    expect(screen.getByText(/Quem pode editar este workspace cria as contas/)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Criar conta' })).toBeNull()
  })

  it('o erro de carga mostra o aviso com "Tentar de novo", que consulta outra vez', async () => {
    const { api, user } = open([carteira])
    api.on(wsRoute('GET', WID, '/accounts'), networkError())
    await screen.findByRole('alert')
    expect(screen.getByRole('alert')).toHaveTextContent('Não foi possível carregar as contas. Tente de novo.')

    api.on(wsRoute('GET', WID, '/accounts'), ok([carteira]))
    await user.click(screen.getByRole('button', { name: 'Tentar de novo' }))

    expect(await screen.findByRole('list', { name: 'Contas' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('se só o relatório de saldo falha, também é erro (a tela nunca mostra lista com total errado)', async () => {
    const { api } = open([carteira])
    api.on(wsRoute('GET', WID, '/reports/balance'), problem(500, 'Erro'))

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar as contas.')
    expect(screen.queryByRole('list', { name: 'Contas' })).toBeNull()
    expect(screen.queryByText('Saldo total')).toBeNull()
  })

  it('o aviso de resultado vem do estado do roteador e aparece uma vez', async () => {
    open([carteira], 'OWNER', [{ pathname: '/contas', state: { notice: 'Conta criada.' } }])

    expect(await screen.findByText('Conta criada.')).toBeInTheDocument()
  })
})

describe('quem pode o quê', () => {
  it('dono e editor veem "Criar conta"; o leitor, não', async () => {
    for (const role of ['OWNER', 'EDITOR'] as const) {
      const view = open([carteira], role)
      await screen.findByRole('list', { name: 'Contas' })
      expect(screen.getByRole('link', { name: 'Criar conta' })).toBeInTheDocument()
      view.unmount()
    }
    open([carteira], 'VIEWER')
    await screen.findByRole('list', { name: 'Contas' })
    expect(screen.queryByRole('link', { name: 'Criar conta' })).toBeNull()
  })
})

describe('a navegação e a acessibilidade', () => {
  it('"Contas" está na navegação e é o item atual', async () => {
    open([carteira])

    await screen.findByRole('list', { name: 'Contas' })

    expect(screen.getByRole('link', { name: 'Contas' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Início' })).not.toHaveAttribute('aria-current')
  })

  it('passa no axe, com a lista, vazia e com erro', async () => {
    const { container, unmount } = open([carteira, nubank])
    await screen.findByRole('list', { name: 'Contas' })
    await expectNoSeriousViolations(container)
    unmount()

    const empty = open([])
    await screen.findByText('Nenhuma conta ainda')
    await expectNoSeriousViolations(empty.container)
    empty.unmount()

    const failing = open([carteira])
    failing.api.on(wsRoute('GET', WID, '/accounts'), networkError())
    await screen.findByRole('alert')
    await expectNoSeriousViolations(failing.container)
  })
})
