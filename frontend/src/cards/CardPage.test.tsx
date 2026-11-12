import { act, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { CardSummary, Invoice } from '../api/types'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, ok, problem, wsRoute } from '../test/fakeApi'
import { WID, makeAccount, makeBalanceReport, makeCardSummary, makeInvoice, makeMe } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const SUMMARY = wsRoute('GET', WID, '/accounts/a-cartao/card-summary')
const INVOICE = wsRoute('GET', WID, '/accounts/a-cartao/invoices/2026-11')
const ANTERIOR = wsRoute('GET', WID, '/accounts/a-cartao/invoices/2026-10')

const compras: Invoice = makeInvoice({
  lines: [
    { id: 'l1', date: '2026-11-03', description: 'Mercado do Zé', categoryId: 'c1', categoryName: 'Mercado', type: 'EXPENSE', chargeCents: 45000 },
    { id: 'l2', date: '2026-11-05', description: 'Devolução da blusa', categoryId: null, categoryName: null, type: 'EXPENSE', chargeCents: -8000 },
  ],
})

function open(summary: unknown = ok(makeCardSummary()), invoice: unknown = ok(compras), role: 'OWNER' | 'EDITOR' | 'VIEWER' = 'OWNER') {
  const api = fakeApi({ 'GET /api/v1/me': ok(makeMe(role)), [SUMMARY]: summary as never, [INVOICE]: invoice as never })
  return { api, ...renderApp(['/contas/a-cartao/fatura']) }
}

describe('o cartão e a fatura', () => {
  it('mostra limite, devido, disponível e o uso, e a fatura aberta com estado em palavras, período e lançamentos', async () => {
    const { container } = open()

    expect(await screen.findByRole('heading', { level: 1, name: 'Nubank' })).toBeInTheDocument()
    expect(await screen.findByText('Aberta')).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: 'Uso do limite' })).toHaveAttribute('aria-valuenow', '24')
    expect(screen.getByText('Disponível').closest('.tx-stat')).toHaveTextContent(/3\.800,00/)
    expect(screen.getByText(/Período de 11\/10\/2026 a 10\/11\/2026 · fecha em 10\/11\/2026 · vence em 17\/11\/2026/)).toBeInTheDocument()
    const rows = screen.getByRole('list', { name: 'Lançamentos da fatura' })
    expect(rows).toHaveTextContent(/Mercado do Zé.*−R\$\s*450,00/)
    expect(rows).toHaveTextContent(/Devolução da blusa.*Sem categoria.*\+R\$\s*80,00/) // estorno: crédito
    await expectNoSeriousViolations(container)
  })

  it('trocar o mês consulta a fatura do mês e nunca mostra a anterior no lugar', async () => {
    let release!: () => void
    const wait = new Promise<void>((resolve) => {
      release = resolve
    })
    const { api, user } = open()
    api.on(ANTERIOR, async () => (await wait, ok(makeInvoice({ month: '2026-10', status: 'PAID', totalCents: 5000, paidCents: 5000, remainingCents: 0, lines: [] }))))
    await screen.findByText('Aberta')

    await user.click(screen.getByRole('button', { name: 'Mês anterior' }))

    expect(screen.getByText('Carregando a fatura…')).toBeInTheDocument()
    expect(screen.queryByText('Mercado do Zé')).toBeNull()
    await act(async () => release())
    expect(await screen.findByText('Paga')).toBeInTheDocument()
    expect(screen.getByText('Nenhum lançamento nesta fatura.')).toBeInTheDocument()
  })

  it('sem limite diz "Sem limite cadastrado"; passando do limite diz quanto', async () => {
    const semLimite: CardSummary = makeCardSummary({ limitCents: null, availableCents: null })
    const first = open(ok(semLimite))
    expect(await screen.findByText('Sem limite cadastrado.')).toBeInTheDocument()
    expect(screen.queryByRole('progressbar', { name: 'Uso do limite' })).toBeNull()
    first.unmount()

    open(ok(makeCardSummary({ owedCents: 560000, availableCents: -60000 })))
    expect(await screen.findByText(/Passou R\$\s*600,00 do limite/)).toBeInTheDocument()
  })

  it('sem os dias de fechamento e vencimento: explica e leva à edição (quem pode escrever)', async () => {
    const owner = open(ok(makeCardSummary({ openInvoice: null })))
    expect(await screen.findByText('Faturas indisponíveis')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Editar a conta' })).toHaveAttribute('href', '/contas/a-cartao')
    owner.unmount()

    open(ok(makeCardSummary({ openInvoice: null })), undefined, 'VIEWER')
    expect(await screen.findByText('Faturas indisponíveis')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Editar a conta' })).toBeNull()
  })

  it('conta que não é cartão mostra a mensagem da API; cartão que não existe, "não encontrado"; falha, "Tentar de novo"', async () => {
    const naoCartao = open(problem(409, 'Conflito de estado', { detail: 'A conta não é um cartão de crédito' }))
    expect(await screen.findByText('A conta não é um cartão de crédito')).toBeInTheDocument()
    naoCartao.unmount()

    const naoExiste = open(problem(404, 'Não encontrado'))
    expect(await screen.findByRole('heading', { name: 'Cartão não encontrado' })).toBeInTheDocument()
    naoExiste.unmount()

    open(networkError())
    expect(await screen.findByRole('button', { name: 'Tentar de novo' })).toBeInTheDocument()
  })
})

describe('o atalho "Fatura" na lista de contas', () => {
  it('só os cartões de crédito ativos têm, inclusive para o leitor', async () => {
    const cartao = makeAccount({ id: 'a-cartao', name: 'Nubank', type: 'CREDIT_CARD', balanceCents: -1000 })
    const velho = makeAccount({ id: 'a-velho', name: 'Cartão velho', type: 'CREDIT_CARD', archived: true })
    const corrente = makeAccount({ id: 'a-corrente', name: 'Corrente', type: 'CHECKING' })
    const accounts = [cartao, velho, corrente]
    fakeApi({
      'GET /api/v1/me': ok(makeMe('VIEWER')),
      [wsRoute('GET', WID, '/accounts')]: (call) => ok(call.path.includes('includeArchived=true') ? accounts : accounts.filter((a) => !a.archived)),
      [wsRoute('GET', WID, '/reports/balance')]: ok(makeBalanceReport(accounts)),
    })
    const { user } = renderApp(['/contas'])

    const link = await screen.findByRole('link', { name: 'Fatura de Nubank' })
    expect(link).toHaveAttribute('href', '/contas/a-cartao/fatura')
    expect(screen.queryByRole('link', { name: /Fatura de Corrente/ })).toBeNull()

    await user.click(screen.getByRole('checkbox', { name: 'Mostrar arquivadas' }))
    await screen.findByText('Cartão velho')
    expect(screen.queryByRole('link', { name: 'Fatura de Cartão velho' })).toBeNull()
  })
})
