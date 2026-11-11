import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Account, CategoryTree, Transaction } from '../api/types'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, ok, problem, wsRoute, type Handler } from '../test/fakeApi'
import { installTransactions } from '../test/fakeTransactions'
import { WID, makeAccount, makeCategory, makeCategoryTree, makeMe, makeTransaction } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const carteira = makeAccount({ id: 'a-carteira', name: 'Carteira' })
const reserva = makeAccount({ id: 'a-reserva', name: 'Reserva' })
const antiga = makeAccount({ id: 'a-antiga', name: 'Antiga', archived: true })
const poupanca = makeAccount({ id: 'a-poupanca', name: 'Poupança' })
const accounts: Account[] = [carteira, reserva, antiga, poupanca]
const mercado = makeCategory({ id: 'c-mercado', name: 'Mercado' })
const alimentacao: CategoryTree = makeCategoryTree({ id: 'c-alim', name: 'Alimentação', kind: 'EXPENSE' }, [mercado])
const salario = makeCategoryTree({ id: 'c-sal', name: 'Salário', kind: 'INCOME' })
const velha = makeCategoryTree({ id: 'c-velha', name: 'Antiga', kind: 'EXPENSE', archived: true })
const categories = [alimentacao, salario, velha]

const lunch = () => makeTransaction({ id: 'tx-lunch', accountId: 'a-carteira', categoryId: 'c-mercado', amountCents: -3590, description: 'Almoço', occurredOn: '2026-11-03', notes: 'com o time' })
const pay = () => makeTransaction({ id: 'tx-pay', type: 'INCOME', accountId: 'a-reserva', categoryId: 'c-sal', amountCents: 500000, description: 'Salário', occurredOn: '2026-11-05' })
const transferEnds = () => [
  makeTransaction({ id: 'tx-out', type: 'TRANSFER', accountId: 'a-carteira', counterpartAccountId: 'a-reserva', amountCents: -10000, description: 'Para a reserva', occurredOn: '2026-11-06', transferGroupId: 'g1', categoryId: null }),
  makeTransaction({ id: 'tx-in', type: 'TRANSFER', accountId: 'a-reserva', counterpartAccountId: 'a-carteira', amountCents: 10000, description: 'Para a reserva', occurredOn: '2026-11-06', transferGroupId: 'g1', categoryId: null }),
]

function open(initial: Transaction[], id: string, { role = 'OWNER' as 'OWNER' | 'EDITOR' | 'VIEWER', put, get }: { role?: 'OWNER' | 'EDITOR' | 'VIEWER'; put?: Handler; get?: Handler } = {}) {
  const api = fakeApi({
    'GET /api/v1/me': ok(makeMe(role)),
    [wsRoute('GET', WID, '/accounts')]: ok(accounts),
    [wsRoute('GET', WID, '/categories')]: ok(categories),
  })
  const memory = installTransactions(api, initial, { workspaceId: WID, categories })
  const PUT = wsRoute('PUT', WID, `/transactions/${id}`)
  if (put) api.on(PUT, put)
  if (get) api.on(wsRoute('GET', WID, `/transactions/${id}`), get)
  return { api, memory, PUT, ...renderApp([`/lancamentos/${id}`]) }
}
type Ctx = ReturnType<typeof open>

const editReady = (title = 'Editar lançamento') => screen.findByRole('heading', { name: title })
const amount = () => screen.getByLabelText('Valor')
const date = () => screen.getByLabelText('Data')
const description = () => screen.getByLabelText('Descrição')
const account = () => screen.getByLabelText(/^(Conta|De)$/)
const destination = () => screen.getByLabelText('Para')
const category = () => screen.getByLabelText('Categoria')
const notes = () => screen.getByLabelText(/^Observações/)
const save = () => screen.getByRole('button', { name: /^(Salvar|Salvando…)$/ })
const putBody = (ctx: Ctx) => ctx.api.callsTo(ctx.PUT)[0].body as Record<string, unknown>

describe('o caminho até a edição', () => {
  it('tocar numa linha da lista abre o lançamento num endereço próprio, com tudo preenchido', async () => {
    const api = fakeApi({
      'GET /api/v1/me': ok(makeMe('OWNER')),
      [wsRoute('GET', WID, '/accounts')]: ok(accounts),
      [wsRoute('GET', WID, '/categories')]: ok(categories),
    })
    installTransactions(api, [lunch()], { workspaceId: WID, categories })
    const view = renderApp(['/lancamentos'])
    await screen.findByText('1 lançamento')

    await view.user.click(screen.getByRole('button', { name: /Almoço/ }))

    expect(await editReady()).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/lancamentos/tx-lunch')
    expect(description()).toHaveValue('Almoço')
  })

  it('recarregar em /lancamentos/:id abre direto o formulário daquele lançamento', async () => {
    open([lunch()], 'tx-lunch')

    expect(await editReady()).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Lançamentos' })).toHaveAttribute('aria-current', 'page')
  })
})

describe('editar um lançamento', () => {
  it('abre preenchido: o tipo visível e não editável, o valor positivo no formato do campo, a data, a conta e a categoria', async () => {
    open([lunch()], 'tx-lunch')
    await editReady()

    expect(screen.getByLabelText('Tipo')).toHaveValue('EXPENSE')
    expect(screen.getByLabelText('Tipo')).toBeDisabled()
    expect(amount()).toHaveValue('35,90')
    expect(date()).toHaveValue('2026-11-03')
    expect(description()).toHaveValue('Almoço')
    expect(account()).toHaveValue('a-carteira')
    expect(category()).toHaveValue('c-mercado')
    expect(notes()).toHaveValue('com o time')
    expect(screen.getByRole('checkbox', { name: 'Pendente (ainda não aconteceu)' })).not.toBeChecked()
    expect(screen.queryByRole('radio')).toBeNull() // o tipo não se escolhe na edição
  })

  it('salvar envia a edição completa (sem o tipo), volta à lista com o aviso e a lista mostra o novo valor', async () => {
    const ctx = open([lunch()], 'tx-lunch')
    await editReady()

    await ctx.user.clear(amount())
    await ctx.user.type(amount(), '40,00')
    await ctx.user.clear(description())
    await ctx.user.type(description(), '  Almoço com o time  ')
    await ctx.user.click(save())

    expect(await screen.findByText('Lançamento salvo.')).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: /Almoço com o time/ })).toHaveTextContent(/−R\$\s*40,00/)
    expect(putBody(ctx)).toEqual({ accountId: 'a-carteira', amountCents: 4000, occurredOn: '2026-11-03', description: 'Almoço com o time', notes: 'com o time', categoryId: 'c-mercado', status: 'CLEARED' })
    expect('type' in putBody(ctx)).toBe(false)
    expect(ctx.memory.store[0].amountCents).toBe(-4000)
  })

  it('uma receita volta positiva depois de editada', async () => {
    const ctx = open([pay()], 'tx-pay')
    await editReady()

    await ctx.user.clear(amount())
    await ctx.user.type(amount(), '6.000,00')
    await ctx.user.click(save())

    await screen.findByText('Lançamento salvo.')
    expect(ctx.memory.store[0].amountCents).toBe(600000)
  })

  it('apagar as observações as limpa (a edição é completa: ausente limpa)', async () => {
    const ctx = open([lunch()], 'tx-lunch')
    await editReady()

    await ctx.user.clear(notes())
    await ctx.user.click(save())

    await screen.findByText('Lançamento salvo.')
    expect('notes' in putBody(ctx)).toBe(false)
    expect(ctx.memory.store[0].notes).toBeNull()
  })

  it('as mesmas regras do cadastro valem: erro no campo, foco no primeiro, nada enviado', async () => {
    const ctx = open([lunch()], 'tx-lunch')
    await editReady()
    await ctx.user.clear(amount())
    await ctx.user.clear(description())

    await ctx.user.click(save())

    expect(amount()).toHaveAccessibleDescription('Informe o valor.')
    expect(description()).toHaveAccessibleDescription('Informe a descrição.')
    expect(amount()).toHaveFocus()
    expect(ctx.api.callsTo(ctx.PUT)).toHaveLength(0)
  })

  it('trocar a categoria e a conta', async () => {
    const ctx = open([lunch()], 'tx-lunch')
    await editReady()

    await ctx.user.selectOptions(account(), 'a-reserva')
    await ctx.user.selectOptions(category(), 'c-alim')
    await ctx.user.click(save())

    await screen.findByText('Lançamento salvo.')
    expect(putBody(ctx)).toMatchObject({ accountId: 'a-reserva', categoryId: 'c-alim' })
  })

  it('a conta e a categoria arquivadas que o lançamento já usa aparecem marcadas, escolhidas', async () => {
    open([makeTransaction({ id: 'tx-velho', accountId: 'a-antiga', categoryId: 'c-velha', amountCents: -500, description: 'Antigo' })], 'tx-velho')
    await editReady()

    expect(account()).toHaveValue('a-antiga')
    expect(within(account()).getByRole('option', { name: 'Antiga (arquivada)' })).toBeInTheDocument()
    expect(category()).toHaveValue('c-velha')
    expect(within(category()).getByRole('option', { name: 'Antiga (arquivada)' })).toBeInTheDocument()
  })

  it('a recusa da API de uma parcela ou recorrência aparece como a API disse, e os valores ficam', async () => {
    const parcela = makeTransaction({ id: 'tx-parc', accountId: 'a-carteira', categoryId: 'c-mercado', amountCents: -1000, description: 'Parcela', installmentGroupId: 'ig', installmentNumber: 2, installmentCount: 5 })
    const ctx = open([parcela], 'tx-parc', { put: problem(409, 'Conflito', { detail: 'O valor de uma parcela não pode ser editado' }) })
    await editReady()

    await ctx.user.click(save())

    expect(await screen.findByRole('alert')).toHaveTextContent('O valor de uma parcela não pode ser editado')
    expect(description()).toHaveValue('Parcela')
  })

  it('409 de regra, 403 e falha de rede ao salvar avisam e mantêm o formulário', async () => {
    const forbidden = open([lunch()], 'tx-lunch', { put: problem(403, 'Proibido') })
    await editReady()
    await forbidden.user.click(save())
    expect(await screen.findByRole('alert')).toHaveTextContent('Você não tem permissão para alterar isto neste workspace.')
    forbidden.unmount()

    const offline = open([lunch()], 'tx-lunch', { put: networkError() })
    await editReady()
    await offline.user.click(save())
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível falar com o servidor. Tente de novo.')
    expect(screen.getByRole('button', { name: 'Tentar de novo' })).toBeInTheDocument()
  })

  it('404 ao salvar (excluído em outro aparelho): volta à lista atualizada, com o aviso', async () => {
    const ctx = open([lunch()], 'tx-lunch', { put: problem(404, 'Não encontrado') })
    await editReady()

    await ctx.user.click(save())

    expect(await screen.findByText('Não encontrada. A lista foi atualizada.')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Lançamentos' })).toBeInTheDocument()
  })

  it('cancelar volta à lista sem salvar', async () => {
    const ctx = open([lunch()], 'tx-lunch')
    await editReady()
    await ctx.user.type(description(), ' mudou')

    await ctx.user.click(screen.getByRole('link', { name: 'Cancelar' }))

    expect(await screen.findByRole('heading', { name: 'Lançamentos' })).toBeInTheDocument()
    expect(ctx.api.callsTo(ctx.PUT)).toHaveLength(0)
  })
})

describe('uma transferência', () => {
  it.each([
    ['tx-out', 'a ponta de saída'],
    ['tx-in', 'a ponta de entrada'],
  ])('abre com a origem e o destino reais por %s (%s)', async (id) => {
    open(transferEnds(), id)
    await editReady('Editar transferência')

    expect(screen.getByLabelText('De')).toHaveValue('a-carteira')
    expect(destination()).toHaveValue('a-reserva')
    expect(amount()).toHaveValue('100,00')
    expect(screen.queryByLabelText('Categoria')).toBeNull()
  })

  it('salvar pela ponta de destino envia a origem como accountId e muda as duas pontas', async () => {
    const ctx = open(transferEnds(), 'tx-in')
    await editReady('Editar transferência')

    await ctx.user.clear(amount())
    await ctx.user.type(amount(), '25,00')
    await ctx.user.selectOptions(destination(), 'a-poupanca')
    await ctx.user.click(save())

    await screen.findByText('Lançamento salvo.')
    expect(putBody(ctx)).toEqual({ accountId: 'a-carteira', destinationAccountId: 'a-poupanca', amountCents: 2500, occurredOn: '2026-11-06', description: 'Para a reserva', status: 'CLEARED' })
    const out = ctx.memory.store.find((end) => end.amountCents < 0)!
    const into = ctx.memory.store.find((end) => end.amountCents > 0)!
    expect(out).toMatchObject({ accountId: 'a-carteira', amountCents: -2500 })
    expect(into).toMatchObject({ accountId: 'a-poupanca', amountCents: 2500 })
  })

  it('origem igual ao destino é recusada no campo do destino', async () => {
    const ctx = open(transferEnds(), 'tx-out')
    await editReady('Editar transferência')

    await ctx.user.selectOptions(destination(), 'a-carteira')
    await ctx.user.click(save())

    expect(destination()).toHaveAccessibleDescription('A origem e o destino precisam ser diferentes.')
    expect(ctx.api.callsTo(ctx.PUT)).toHaveLength(0)
  })
})

describe('o estado pendente na edição', () => {
  it('um pendente abre com a caixa marcada; desmarcar envia efetivado, e marcar um efetivado envia pendente', async () => {
    const pending = open([makeTransaction({ id: 'tx-p', accountId: 'a-carteira', categoryId: 'c-mercado', amountCents: -100, description: 'A pagar', status: 'PENDING' })], 'tx-p')
    await editReady()
    expect(screen.getByRole('checkbox', { name: 'Pendente (ainda não aconteceu)' })).toBeChecked()
    await pending.user.click(screen.getByRole('checkbox', { name: 'Pendente (ainda não aconteceu)' }))
    await pending.user.click(save())
    await screen.findByText('Lançamento salvo.')
    expect(putBody(pending).status).toBe('CLEARED')
    pending.unmount()

    const cleared = open([lunch()], 'tx-lunch')
    await editReady()
    await cleared.user.click(screen.getByRole('checkbox', { name: 'Pendente (ainda não aconteceu)' }))
    await cleared.user.click(save())
    await screen.findByText('Lançamento salvo.')
    expect(putBody(cleared).status).toBe('PENDING')
    expect(cleared.memory.store[0].status).toBe('PENDING')
  })
})

describe('os outros casos de /lancamentos/:id', () => {
  it('um lançamento que não existe (ou é de outro workspace) é "não encontrado", com a volta', async () => {
    open([lunch()], 'nao-existe')

    expect(await screen.findByRole('heading', { name: 'Lançamento não encontrado' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Voltar aos lançamentos' })).toHaveAttribute('href', '/lancamentos')
    expect(screen.queryByLabelText('Valor')).toBeNull()
  })

  it('falha ao carregar o lançamento: aviso com "Tentar de novo", que consulta outra vez', async () => {
    const ctx = open([lunch()], 'tx-lunch', { get: networkError() })
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar o lançamento. Tente de novo.')

    ctx.api.on(wsRoute('GET', WID, '/transactions/tx-lunch'), ok(lunch()))
    await ctx.user.click(screen.getByRole('button', { name: 'Tentar de novo' }))

    expect(await editReady()).toBeInTheDocument()
  })

  it('passa no axe (formulário e transferência)', async () => {
    const form = open([lunch()], 'tx-lunch')
    await editReady()
    await expectNoSeriousViolations(form.container)
    form.unmount()

    const transfer = open(transferEnds(), 'tx-in')
    await editReady('Editar transferência')
    await expectNoSeriousViolations(transfer.container)
  })
})

describe('o leitor', () => {
  it('consulta o lançamento só para leitura: sem formulário e sem nenhuma ação', async () => {
    const ctx = open([lunch()], 'tx-lunch', { role: 'VIEWER' })

    expect(await screen.findByRole('heading', { name: 'Lançamento' })).toBeInTheDocument()
    expect(screen.getByText('Almoço')).toBeInTheDocument()
    expect(screen.getByText('03/11/2026')).toBeInTheDocument()
    expect(screen.getByText('Carteira')).toBeInTheDocument()
    expect(screen.getByText('Alimentação › Mercado')).toBeInTheDocument()
    expect(screen.getByText('com o time')).toBeInTheDocument()
    expect(screen.getByText('Efetivado')).toBeInTheDocument()
    expect(screen.queryByLabelText('Valor')).toBeNull()
    expect(screen.queryByRole('button', { name: /^(Salvar|Efetivar|Excluir)/ })).toBeNull()
    expect(ctx.api.callsTo(ctx.PUT)).toHaveLength(0)
  })

  it('a transferência mostra "De" e "Para", e um pendente com selos mostra o estado e a origem', async () => {
    open(transferEnds(), 'tx-in', { role: 'VIEWER' })
    expect(await screen.findByRole('heading', { name: 'Transferência' })).toBeInTheDocument()
    expect(screen.getByText('De').nextElementSibling).toHaveTextContent('Carteira')
    expect(screen.getByText('Para').nextElementSibling).toHaveTextContent('Reserva')
  })

  it('selos de parcela e de estado pendente aparecem, e o axe passa', async () => {
    const parcela = makeTransaction({ id: 'tx-parc', accountId: 'a-carteira', categoryId: 'c-mercado', amountCents: -1000, description: 'Parcela', status: 'PENDING', installmentNumber: 2, installmentCount: 5, imported: true })
    const ctx = open([parcela], 'tx-parc', { role: 'VIEWER' })

    expect(await screen.findByText('Pendente')).toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Origem do lançamento' })).toHaveTextContent('Parcela 2 de 5')
    expect(screen.getByRole('list', { name: 'Origem do lançamento' })).toHaveTextContent('Importado')
    await waitFor(async () => expectNoSeriousViolations(ctx.container))
  })
})
