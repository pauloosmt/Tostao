import { act, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { currentMonth, monthLabel, shiftMonth } from '../dates/months'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, ok, wsRoute } from '../test/fakeApi'
import { WID, makeBudgetItem, makeMe, makeMonthBudget } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const MES = currentMonth()
const ANTES = shiftMonth(MES, -1) as string
const ROUTE = wsRoute('GET', WID, '/monthly-budgets')

const geral = makeBudgetItem({ id: 'b-geral', categoryId: null, categoryName: null, amountCents: 300000, spentCents: 120000, remainingCents: 180000, percentUsed: 40 })
const mercado = makeBudgetItem({ id: 'b-mercado', categoryId: 'c-mercado', categoryName: 'Mercado', amountCents: 80000, spentCents: 68000, remainingCents: 12000, percentUsed: 85, status: 'WARNING' })
const lazer = makeBudgetItem({ id: 'b-lazer', categoryId: 'c-lazer', categoryName: 'Lazer', amountCents: 20000, spentCents: 24000, remainingCents: -4000, percentUsed: 120, status: 'EXCEEDED' })
const antiga = makeBudgetItem({ id: 'b-antiga', categoryId: 'c-antiga', categoryName: 'Antiga', categoryArchived: true, amountCents: 10000, spentCents: 0, remainingCents: 10000, percentUsed: 0 })

function open(handler = ok(makeMonthBudget([geral, mercado, lazer, antiga], { totalSpentCents: 212000, spentOutsideCategoryLimitsCents: 20000 }))) {
  const api = fakeApi({ 'GET /api/v1/me': ok(makeMe('OWNER')), [ROUTE]: handler })
  return { api, ...renderApp(['/orcamentos']) }
}

describe('o orçamento do mês', () => {
  it('mostra o limite geral e os de categoria com gasto, restante e estado em palavras, e o total fora dos limites', async () => {
    const { container } = open()

    expect(await screen.findByRole('progressbar', { name: 'Mercado' })).toBeInTheDocument()
    const general = within(screen.getByRole('region', { name: 'Limite geral' }))
    expect(general.getByText(/Restam R\$\s*1\.800,00/)).toBeInTheDocument()
    expect(screen.getByText(/R\$\s*680,00 de R\$\s*800,00/)).toBeInTheDocument()
    expect(screen.getByText('Perto do limite')).toBeInTheDocument() // 85%
    expect(screen.getByText('Limite atingido')).toBeInTheDocument() // 120%
    expect(screen.getByText(/Passou R\$\s*40,00 do limite/)).toBeInTheDocument()
    expect(screen.getByText('Arquivada')).toBeInTheDocument()
    expect(screen.getByText(/Total gasto no mês: R\$\s*2\.120,00\. Fora dos limites de categoria: R\$\s*200,00\./)).toBeInTheDocument()
    await expectNoSeriousViolations(container)
  })

  it('trocar o mês consulta o mês novo e nunca mostra o anterior no lugar', async () => {
    let release!: () => void
    const wait = new Promise<void>((resolve) => {
      release = resolve
    })
    const { api, user } = open()
    api.on(ROUTE, async (call) => {
      if (call.path.includes(`month=${ANTES}`)) await wait
      return ok(makeMonthBudget([mercado], { totalSpentCents: 68000 }))
    })
    await screen.findByRole('progressbar', { name: 'Mercado' })

    await user.click(screen.getByRole('button', { name: 'Mês anterior' }))

    expect(screen.getByText(monthLabel(ANTES))).toBeInTheDocument()
    expect(screen.getByText('Carregando o orçamento do mês…')).toBeInTheDocument()
    expect(screen.queryByRole('progressbar')).toBeNull()
    await act(async () => release())
    expect(await screen.findByRole('progressbar', { name: 'Mercado' })).toBeInTheDocument()
    expect(api.callsTo(ROUTE).map((call) => call.path.split('month=')[1])).toEqual([MES, ANTES])
  })

  it('sem limites: explica e mostra o gasto do mês', async () => {
    open(ok(makeMonthBudget([], { totalSpentCents: 5000 })))

    expect(await screen.findByText('Nenhum limite neste mês')).toBeInTheDocument()
    expect(screen.getByText('Gasto no mês').closest('.tx-stat')).toHaveTextContent(/50,00/)
  })

  it('se não carrega, avisa com "Tentar de novo"', async () => {
    open(networkError() as never)

    expect(await screen.findByRole('button', { name: 'Tentar de novo' })).toBeInTheDocument()
  })
})
