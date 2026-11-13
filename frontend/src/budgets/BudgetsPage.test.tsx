import { act, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { currentMonth, monthLabel, shiftMonth } from '../dates/months'
import { currentWeek, shiftWeek, weekLabel } from '../dates/weeks'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, ok, problem, wsRoute } from '../test/fakeApi'
import { WID, makeBudgetItem, makeCategory, makeCategoryTree, makeMe, makeMonthBudget, makeWeekBudget } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const MES = currentMonth()
const ANTES = shiftMonth(MES, -1) as string
const ROUTE = wsRoute('GET', WID, '/monthly-budgets')

const geral = makeBudgetItem({ id: 'b-geral', categoryId: null, categoryName: null, amountCents: 300000, spentCents: 120000, remainingCents: 180000, percentUsed: 40 })
const mercado = makeBudgetItem({ id: 'b-mercado', categoryId: 'c-mercado', categoryName: 'Mercado', amountCents: 80000, spentCents: 68000, remainingCents: 12000, percentUsed: 85, status: 'WARNING' })
const lazer = makeBudgetItem({ id: 'b-lazer', categoryId: 'c-lazer', categoryName: 'Lazer', amountCents: 20000, spentCents: 24000, remainingCents: -4000, percentUsed: 120, status: 'EXCEEDED' })
const antiga = makeBudgetItem({ id: 'b-antiga', categoryId: 'c-antiga', categoryName: 'Antiga', categoryArchived: true, amountCents: 10000, spentCents: 0, remainingCents: 10000, percentUsed: 0 })

const categorias = [
  makeCategoryTree({ id: 'c-mercado', name: 'Mercado', kind: 'EXPENSE' }, [makeCategory({ id: 'c-feira', name: 'Feira' })]),
  makeCategoryTree({ id: 'c-sal', name: 'Salário', kind: 'INCOME' }),
]
const PUT = wsRoute('PUT', WID, '/monthly-budgets')
const COPY = wsRoute('POST', WID, '/monthly-budgets/copy')

function open(handler = ok(makeMonthBudget([geral, mercado, lazer, antiga], { totalSpentCents: 212000, spentOutsideCategoryLimitsCents: 20000 })), role: 'OWNER' | 'EDITOR' | 'VIEWER' = 'OWNER') {
  const api = fakeApi({ 'GET /api/v1/me': ok(makeMe(role)), [ROUTE]: handler, [wsRoute('GET', WID, '/categories')]: ok(categorias) })
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

describe('definir, editar, remover e copiar', () => {
  it('um limite novo: escolhe só categorias de despesa ativas, envia o valor em centavos e a tela se atualiza', async () => {
    const { api, user } = open()
    await screen.findByRole('progressbar', { name: 'Mercado' })
    api.on(PUT, ok({ id: 'novo', month: MES, categoryId: 'c-feira', categoryName: 'Mercado › Feira', amountCents: 15050 }, 201))

    await user.click(screen.getByRole('button', { name: 'Novo limite' }))
    const category = await screen.findByLabelText('Categoria')
    expect(Array.from(category.querySelectorAll('option')).map((o) => o.textContent)).toEqual(['Geral (todas as despesas)', 'Mercado', 'Mercado › Feira'])
    await user.selectOptions(category, 'c-feira')
    await user.type(screen.getByLabelText('Limite do mês'), '150,50')
    await user.click(screen.getByRole('button', { name: 'Salvar' }))

    expect(await screen.findByText('Limite salvo.')).toBeInTheDocument()
    expect(api.callsTo(PUT)[0].body).toEqual({ month: MES, categoryId: 'c-feira', amountCents: 15050 })
    expect(screen.queryByLabelText('Limite do mês')).toBeNull()
    expect(api.callsTo(ROUTE)).toHaveLength(2) // a lista recarregou
  })

  it('o limite geral não envia categoria; valor vazio pede o valor e nada é enviado; zero é aceito', async () => {
    const { api, user } = open()
    await screen.findByRole('progressbar', { name: 'Mercado' })
    api.on(PUT, ok({ id: 'g', month: MES, categoryId: null, categoryName: null, amountCents: 0 }))
    await user.click(screen.getByRole('button', { name: 'Novo limite' }))

    await user.click(await screen.findByRole('button', { name: 'Salvar' }))
    expect(await screen.findByText('Informe o valor do limite.')).toBeInTheDocument()
    expect(api.callsTo(PUT)).toHaveLength(0)

    await user.type(screen.getByLabelText('Limite do mês'), '0')
    await user.click(screen.getByRole('button', { name: 'Salvar' }))

    expect(await screen.findByText('Limite salvo.')).toBeInTheDocument()
    expect(api.callsTo(PUT)[0].body).toEqual({ month: MES, amountCents: 0 })
  })

  it('uma recusa da API aparece como ela disse e o valor digitado fica', async () => {
    const { api, user } = open()
    await screen.findByRole('progressbar', { name: 'Mercado' })
    api.on(PUT, problem(409, 'Conflito', { detail: 'A categoria está arquivada' }))
    await user.click(screen.getByRole('button', { name: 'Novo limite' }))

    await user.type(await screen.findByLabelText('Limite do mês'), '1000')
    await user.click(screen.getByRole('button', { name: 'Salvar' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('A categoria está arquivada')
    expect(screen.getByLabelText('Limite do mês')).toHaveValue('10,00')
  })

  it('editar troca só o valor (com o valor atual preenchido)', async () => {
    const { api, user } = open()
    const put = wsRoute('PUT', WID, '/monthly-budgets/b-mercado')
    api.on(put, ok({ id: 'b-mercado', month: MES, categoryId: 'c-mercado', categoryName: 'Mercado', amountCents: 90000 }))
    await screen.findByRole('progressbar', { name: 'Mercado' })

    await user.click(screen.getByRole('button', { name: 'Editar limite de Mercado' }))
    const amount = await screen.findByLabelText('Limite do mês')
    expect(amount).toHaveValue('800,00')
    await user.clear(amount)
    await user.type(amount, '90000')
    await user.click(screen.getByRole('button', { name: 'Salvar' }))

    expect(await screen.findByText('Limite atualizado.')).toBeInTheDocument()
    expect(api.callsTo(put)[0].body).toEqual({ amountCents: 90000 })
  })

  it('remover pede confirmação: cancelar não remove, confirmar remove e a lista recarrega', async () => {
    const { api, user } = open()
    const del = wsRoute('DELETE', WID, '/monthly-budgets/b-lazer')
    api.on(del, () => ({ status: 204 }) as never)
    await screen.findByRole('progressbar', { name: 'Lazer' })

    await user.click(screen.getByRole('button', { name: 'Remover limite de Lazer' }))
    const confirm = await screen.findByRole('dialog', { name: 'Remover este limite?' })
    await user.click(within(confirm).getByRole('button', { name: 'Cancelar' }))
    expect(api.callsTo(del)).toHaveLength(0)

    await user.click(screen.getByRole('button', { name: 'Remover limite de Lazer' }))
    await user.click(within(await screen.findByRole('dialog', { name: 'Remover este limite?' })).getByRole('button', { name: 'Remover' }))

    expect(await screen.findByText('Limite removido.')).toBeInTheDocument()
    expect(api.callsTo(del)).toHaveLength(1)
    expect(api.callsTo(ROUTE)).toHaveLength(2)
  })

  it('copiar: pede de 1 a 24 meses e conta o que foi criado, o que já existia e o que foi ignorado', async () => {
    const { api, user } = open()
    api.on(COPY, ok({ created: 3, skippedExisting: 1, skippedArchivedCategory: 2 }))
    await screen.findByRole('progressbar', { name: 'Mercado' })

    await user.click(screen.getByRole('button', { name: 'Copiar para os próximos meses' }))
    const months = await screen.findByLabelText('Quantos meses')
    await user.clear(months)
    await user.type(months, '25')
    await user.click(screen.getByRole('button', { name: 'Copiar limites' }))
    expect(await screen.findByText('Informe de 1 a 24 meses.')).toBeInTheDocument()
    expect(api.callsTo(COPY)).toHaveLength(0)

    await user.clear(months)
    await user.type(months, '6')
    await user.click(screen.getByRole('button', { name: 'Copiar limites' }))

    expect(await screen.findByText('3 limites criados, 1 já existia, 2 ignorados por categoria arquivada.')).toBeInTheDocument()
    expect(api.callsTo(COPY)[0].body).toEqual({ fromMonth: MES, months: 6 })
  })

  it('o leitor vê tudo e nenhuma ação', async () => {
    open(undefined, 'VIEWER')

    expect(await screen.findByRole('progressbar', { name: 'Mercado' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Novo limite|Copiar|Editar limite|Remover limite/ })).toBeNull()
  })
})

describe('os limites semanais', () => {
  const SEMANA = currentWeek()
  const ANTES_SEMANA = shiftWeek(SEMANA, -1) as string
  const WROUTE = wsRoute('GET', WID, '/budgets')
  const semanal = makeWeekBudget([makeBudgetItem({ id: 'w-mercado', categoryId: 'c-mercado', categoryName: 'Mercado', amountCents: 20000, spentCents: 5000, remainingCents: 15000, percentUsed: 25 })], { totalSpentCents: 5000 })

  function openWeekly(role: 'OWNER' | 'EDITOR' | 'VIEWER' = 'OWNER') {
    const api = fakeApi({
      'GET /api/v1/me': ok(makeMe(role)),
      [ROUTE]: ok(makeMonthBudget([geral])),
      [WROUTE]: ok(semanal),
      [wsRoute('GET', WID, '/categories')]: ok(categorias),
    })
    return { api, ...renderApp(['/orcamentos']) }
  }

  it('"Semanal" mostra a semana corrente com os limites semanais; voltar a "Mensal" mostra o mês', async () => {
    const { api, user } = openWeekly()
    await screen.findByRole('progressbar', { name: 'Todas as despesas' })

    await user.click(await screen.findByRole('radio', { name: 'Semanal' }))

    expect(await screen.findByRole('progressbar', { name: 'Mercado' })).toBeInTheDocument()
    expect(screen.getByText(weekLabel(SEMANA))).toBeInTheDocument()
    expect(screen.getByText('Limites de gasto da semana')).toBeInTheDocument()
    expect(screen.getByText(/Total gasto na semana: R\$\s*50,00/)).toBeInTheDocument()
    expect(api.callsTo(WROUTE).map((call) => call.path.split('week=')[1])).toEqual([SEMANA])

    await user.click(screen.getByRole('radio', { name: 'Mensal' }))
    expect(await screen.findByRole('progressbar', { name: 'Todas as despesas' })).toBeInTheDocument()
    expect(screen.getByText(monthLabel(MES))).toBeInTheDocument()
  })

  it('a semana anterior consulta a semana certa e nunca mostra a outra no lugar', async () => {
    let release!: () => void
    const wait = new Promise<void>((resolve) => {
      release = resolve
    })
    const { api, user } = openWeekly()
    api.on(WROUTE, async (call) => {
      if (call.path.includes(`week=${ANTES_SEMANA}`)) await wait
      return ok(semanal)
    })
    await user.click(await screen.findByRole('radio', { name: 'Semanal' }))
    await screen.findByRole('progressbar', { name: 'Mercado' })

    await user.click(screen.getByRole('button', { name: 'Semana anterior' }))

    expect(screen.getByText(weekLabel(ANTES_SEMANA))).toBeInTheDocument()
    expect(screen.getByText('Carregando o orçamento da semana…')).toBeInTheDocument()
    expect(screen.queryByRole('progressbar')).toBeNull()
    await act(async () => release())
    expect(await screen.findByRole('progressbar', { name: 'Mercado' })).toBeInTheDocument()
  })

  it('um limite novo da semana vai para a rota semanal com o campo "week"; copiar aceita até 52 semanas', async () => {
    const { api, user } = openWeekly()
    const put = wsRoute('PUT', WID, '/budgets')
    const copy = wsRoute('POST', WID, '/budgets/copy')
    api.on(put, ok({ id: 'novo', month: SEMANA, categoryId: 'c-mercado', categoryName: 'Mercado', amountCents: 25000 }, 201))
    api.on(copy, ok({ created: 1, skippedExisting: 0, skippedArchivedCategory: 0 }))
    await user.click(await screen.findByRole('radio', { name: 'Semanal' }))
    await screen.findByRole('progressbar', { name: 'Mercado' })

    await user.click(screen.getByRole('button', { name: 'Novo limite' }))
    await user.selectOptions(await screen.findByLabelText('Categoria'), 'c-mercado')
    await user.type(screen.getByLabelText('Limite da semana'), '25000')
    await user.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByText('Limite salvo.')).toBeInTheDocument()
    expect(api.callsTo(put)[0].body).toEqual({ week: SEMANA, categoryId: 'c-mercado', amountCents: 25000 })

    await user.click(screen.getByRole('button', { name: 'Copiar para as próximas semanas' }))
    const weeks = await screen.findByLabelText('Quantas semanas')
    await user.clear(weeks)
    await user.type(weeks, '53')
    await user.click(screen.getByRole('button', { name: 'Copiar limites' }))
    expect(await screen.findByText('Informe de 1 a 52 semanas.')).toBeInTheDocument()
    await user.clear(weeks)
    await user.type(weeks, '4')
    await user.click(screen.getByRole('button', { name: 'Copiar limites' }))

    expect(await screen.findByText('1 limite criado.')).toBeInTheDocument()
    expect(api.callsTo(copy)[0].body).toEqual({ fromWeek: SEMANA, weeks: 4 })
  })

  it('remover e editar usam a rota semanal; o leitor não tem ações', async () => {
    const { api, user } = openWeekly()
    const del = wsRoute('DELETE', WID, '/budgets/w-mercado')
    api.on(del, () => ({ status: 204 }) as never)
    await user.click(await screen.findByRole('radio', { name: 'Semanal' }))
    await screen.findByRole('progressbar', { name: 'Mercado' })

    await user.click(screen.getByRole('button', { name: 'Remover limite de Mercado' }))
    await user.click(within(await screen.findByRole('dialog', { name: 'Remover este limite?' })).getByRole('button', { name: 'Remover' }))

    expect(await screen.findByText('Limite removido.')).toBeInTheDocument()
    expect(api.callsTo(del)).toHaveLength(1)
  })

  it('o leitor vê a semana e nenhuma ação', async () => {
    const { user } = openWeekly('VIEWER')
    await user.click(await screen.findByRole('radio', { name: 'Semanal' }))

    expect(await screen.findByRole('progressbar', { name: 'Mercado' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Novo limite|Copiar|Editar limite|Remover limite/ })).toBeNull()
  })
})
