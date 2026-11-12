import { act, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Account, CategoryTree, Transaction } from '../api/types'
import { todayISO, previousDay } from '../dates/dates'
import { FAILURE_MESSAGES } from '../forms/apiErrors'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, ok, problem, wsRoute, type Handler } from '../test/fakeApi'
import { installTransactions } from '../test/fakeTransactions'
import { WID, makeAccount, makeCategory, makeCategoryTree, makeInstallmentPlan, makeMe, makeTransaction } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const TODAY = todayISO()
const carteira = makeAccount({ id: 'a-carteira', name: 'Carteira' })
const reserva = makeAccount({ id: 'a-reserva', name: 'Reserva' })
const antiga = makeAccount({ id: 'a-antiga', name: 'Antiga', archived: true })
const accounts: Account[] = [carteira, reserva, antiga]
const mercado = makeCategory({ id: 'c-mercado', name: 'Mercado', color: '#2a9d8f' })
const alimentacao: CategoryTree = makeCategoryTree({ id: 'c-alim', name: 'Alimentação', kind: 'EXPENSE', color: '#e76f51' }, [mercado])
const salario = makeCategoryTree({ id: 'c-sal', name: 'Salário', kind: 'INCOME' })
const categories = [alimentacao, salario]

const POST = wsRoute('POST', WID, '/transactions')
const lunch = () => makeTransaction({ id: 'tx-lunch', accountId: 'a-carteira', categoryId: 'c-mercado', amountCents: -3590, description: 'Almoço', occurredOn: '2026-11-03', notes: 'com o time' })
const bill = (extra: Partial<Transaction> = {}) => makeTransaction({ id: 'tx-bill', accountId: 'a-carteira', categoryId: 'c-mercado', amountCents: -9900, description: 'Conta de luz', status: 'PENDING', occurredOn: '2026-11-20', ...extra })
const transferEnds = () => [
  makeTransaction({ id: 'tx-out', type: 'TRANSFER', accountId: 'a-carteira', counterpartAccountId: 'a-reserva', amountCents: -10000, description: 'Para a reserva', occurredOn: '2026-11-06', transferGroupId: 'g1', categoryId: null }),
  makeTransaction({ id: 'tx-in', type: 'TRANSFER', accountId: 'a-reserva', counterpartAccountId: 'a-carteira', amountCents: 10000, description: 'Para a reserva', occurredOn: '2026-11-06', transferGroupId: 'g1', categoryId: null }),
]

type Role = 'OWNER' | 'EDITOR' | 'VIEWER'

function open(path: string, { role = 'OWNER' as Role, initial = [] as Transaction[], post }: { role?: Role; initial?: Transaction[]; post?: Handler } = {}) {
  const api = fakeApi({
    'GET /api/v1/me': ok(makeMe(role)),
    [wsRoute('GET', WID, '/accounts')]: ok(accounts),
    [wsRoute('GET', WID, '/categories')]: ok(categories),
  })
  const memory = installTransactions(api, initial, { workspaceId: WID, categories })
  if (post) api.on(POST, post)
  return { api, memory, ...renderApp([path]) }
}
type Ctx = ReturnType<typeof open>

const sheet = (title: string) => screen.findByRole('heading', { name: title })
const amount = () => screen.getByLabelText('Valor')
const description = () => screen.getByLabelText('Descrição')
const account = () => screen.getByLabelText(/^(Conta|De)$/)
const destination = () => screen.getByLabelText('Para')
const save = () => screen.getByRole('button', { name: /^(Registrar|Salvar|Salvando…)$/ })
const radio = (name: string) => screen.getByRole('radio', { name })
const body = (api: Ctx['api']) => api.callsTo(POST)[0].body as Record<string, unknown>

async function fillExpense(user: Ctx['user']) {
  await user.type(amount(), '35,90')
  await user.type(description(), 'Almoço')
  await user.selectOptions(account(), 'a-carteira')
  await user.click(radio('Alimentação › Mercado'))
}

describe('registrar na folha', () => {
  it('abre pelo botão da lista com despesa, a data de hoje e nada preenchido; fechar volta sem registrar', async () => {
    const { api, user } = open('/lancamentos')
    await user.click(await screen.findByRole('link', { name: 'Novo lançamento' }))
    await sheet('Novo lançamento')

    expect(screen.getByTestId('location')).toHaveTextContent('/lancamentos/novo')
    expect(radio('Despesa')).toBeChecked()
    expect(amount()).toHaveValue('')
    expect(screen.getByLabelText('Data')).toHaveValue(TODAY)
    expect(radio('Hoje')).toBeChecked()
    expect(account()).toHaveValue('')
    expect(Array.from(account().querySelectorAll('option')).map((o) => o.textContent)).toEqual(['Escolha a conta', 'Carteira', 'Reserva'])
    expect(screen.queryByRole('radio', { name: 'Salário' })).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Fechar' }))

    expect(screen.getByTestId('location').textContent).toBe('/lancamentos')
    expect(screen.queryByLabelText('Valor')).toBeNull()
    expect(api.callsTo(POST)).toHaveLength(0)
  })

  it('a tecla N abre a folha, menos dentro de um campo de texto', async () => {
    const { user } = open('/lancamentos')
    await screen.findByRole('link', { name: 'Novo lançamento' })

    await user.keyboard('n')
    await sheet('Novo lançamento')
    expect(screen.getByTestId('location')).toHaveTextContent('/lancamentos/novo')

    await user.type(description(), 'nnn')
    expect(description()).toHaveValue('nnn')
  })

  it('registra a despesa em centavos inteiros, fecha com o aviso e o lançamento aparece na lista', async () => {
    const { api, memory, user } = open('/lancamentos/novo')
    await sheet('Novo lançamento')

    await user.type(amount(), '1.234,56')
    await user.type(description(), '  Almoço  ')
    await user.selectOptions(account(), 'a-carteira')
    await user.click(radio('Alimentação › Mercado'))
    await user.click(save())

    expect(await screen.findByText('Lançamento registrado.')).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: /Almoço/ })).toHaveTextContent(/−R\$\s*1\.234,56/)
    expect(screen.getByTestId('location').textContent).toBe('/lancamentos')
    expect(screen.queryByLabelText('Valor')).toBeNull()
    expect(body(api)).toEqual({ type: 'EXPENSE', accountId: 'a-carteira', amountCents: 123456, occurredOn: TODAY, description: 'Almoço', categoryId: 'c-mercado' })
    expect(memory.store[0].amountCents).toBe(-123456)
  })

  it('a receita oferece só as categorias de receita; trocar o tipo descarta a categoria que não serve', async () => {
    const { api, user } = open('/lancamentos/novo')
    await sheet('Novo lançamento')
    await user.click(radio('Alimentação'))

    await user.click(radio('Receita'))
    expect(screen.queryByRole('radio', { name: 'Alimentação' })).toBeNull()
    await user.type(amount(), '5.000,00')
    await user.type(description(), 'Salário de novembro')
    await user.selectOptions(account(), 'a-reserva')
    await user.click(radio('Salário'))
    await user.click(save())

    expect(await screen.findByRole('button', { name: /Salário de novembro/ })).toHaveTextContent(/\+R\$\s*5\.000,00/)
    expect(body(api)).toMatchObject({ type: 'INCOME', amountCents: 500000, categoryId: 'c-sal' })
  })

  it('"Ontem" muda a data e "Pendente" envia o estado', async () => {
    const { api, user } = open('/lancamentos/novo')
    await sheet('Novo lançamento')

    await fillExpense(user)
    await user.click(radio('Ontem'))
    await user.click(screen.getByRole('checkbox', { name: 'Pendente (ainda não aconteceu)' }))
    await user.click(save())

    await screen.findByText('Lançamento registrado.')
    expect(body(api)).toMatchObject({ occurredOn: previousDay(TODAY), status: 'PENDING' })
  })

  it('a transferência pede De e Para, cria as duas pontas sem categoria e recusa origem igual ao destino', async () => {
    const { api, memory, user } = open('/lancamentos/novo')
    await sheet('Novo lançamento')

    await user.click(radio('Transferência'))
    expect(screen.queryByText('Categoria')).toBeNull()
    await user.type(amount(), '100,00')
    await user.type(description(), 'Para a reserva')
    await user.selectOptions(account(), 'a-carteira')
    await user.selectOptions(destination(), 'a-carteira')
    await user.click(save())
    expect(await screen.findByText(/não pode ser a mesma|mesma conta|diferente/i)).toBeInTheDocument()
    expect(api.callsTo(POST)).toHaveLength(0)

    await user.selectOptions(destination(), 'a-reserva')
    await user.click(save())

    expect(await screen.findByText('Lançamento registrado.')).toBeInTheDocument()
    expect(body(api)).toMatchObject({ type: 'TRANSFER', accountId: 'a-carteira', destinationAccountId: 'a-reserva', amountCents: 10000 })
    expect(memory.store.map((t) => t.amountCents).sort((a, b) => a - b)).toEqual([-10000, 10000])
  })

  it('vazio: cada campo pede o seu, o foco vai ao valor e nada é enviado', async () => {
    const { api, user } = open('/lancamentos/novo')
    await sheet('Novo lançamento')

    await user.click(save())

    expect(await screen.findAllByText(/./, { selector: '.tx-field__msg--error' })).not.toHaveLength(0)
    expect(amount()).toHaveFocus()
    expect(api.callsTo(POST)).toHaveLength(0)
  })

  it('uma recusa da API aparece como ela disse e os valores ficam; o envio duplicado chama a API uma vez só', async () => {
    let release!: () => void
    const wait = new Promise<void>((resolve) => {
      release = resolve
    })
    const { api, user } = open('/lancamentos/novo', { post: async () => (await wait, problem(409, 'Conflito', { detail: 'A conta está arquivada' })) })
    await sheet('Novo lançamento')
    await fillExpense(user)

    await user.dblClick(save())
    expect(screen.getByRole('button', { name: 'Salvando…' })).toBeDisabled()
    await act(async () => release())

    expect(await screen.findByText('A conta está arquivada')).toBeInTheDocument()
    expect(api.callsTo(POST)).toHaveLength(1)
    expect(description()).toHaveValue('Almoço')
    expect(amount()).toHaveValue('35,90')
  })

  it('servidor fora do ar: avisa com "Tentar de novo", mantém os valores e envia outra vez', async () => {
    const { api, user } = open('/lancamentos/novo', { post: networkError() })
    await sheet('Novo lançamento')
    await fillExpense(user)
    await user.click(save())

    expect(await screen.findByRole('alert')).toHaveTextContent(/servidor/i)
    api.on(POST, ok({}))
    await user.click(screen.getByRole('button', { name: 'Tentar de novo' }))

    expect(await screen.findByText('Lançamento registrado.')).toBeInTheDocument()
  })

  it('o leitor é levado de volta à lista com o aviso, sem formulário', async () => {
    open('/lancamentos/novo', { role: 'VIEWER' })

    expect(await screen.findByText(/só para consulta/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Valor')).toBeNull()
  })

  it('passa no axe: em repouso, como transferência e com erros', async () => {
    const { user, container } = open('/lancamentos/novo')
    await sheet('Novo lançamento')
    await expectNoSeriousViolations(container)

    await user.click(radio('Transferência'))
    await user.click(save())
    await screen.findAllByText(/./, { selector: '.tx-field__msg--error' })
    await expectNoSeriousViolations(container)
  })
})

describe('editar, efetivar e excluir na folha', () => {
  it('tocar numa linha abre a folha preenchida; salvar envia o estado sem o tipo', async () => {
    const { api, memory, user } = open('/lancamentos', { initial: [lunch()] })
    await user.click(await screen.findByRole('button', { name: /Almoço/ }))
    await sheet('Editar lançamento')

    expect(screen.getByTestId('location')).toHaveTextContent('/lancamentos/tx-lunch')
    expect(amount()).toHaveValue('35,90')
    expect(description()).toHaveValue('Almoço')
    expect(account()).toHaveValue('a-carteira')
    expect(radio('Alimentação › Mercado')).toBeChecked()
    expect(screen.queryByRole('radio', { name: 'Receita' })).toBeNull() // o tipo não muda

    await user.clear(amount())
    await user.type(amount(), '40,00')
    await user.click(save())

    expect(await screen.findByText('Lançamento salvo.')).toBeInTheDocument()
    const put = api.callsTo(wsRoute('PUT', WID, '/transactions/tx-lunch'))[0].body as Record<string, unknown>
    expect(put).toMatchObject({ amountCents: 4000, status: 'CLEARED' })
    expect(put).not.toHaveProperty('type')
    expect(memory.store[0].amountCents).toBe(-4000)
  })

  it('a transferência aberta pela ponta de destino traz a origem e o destino reais', async () => {
    open('/lancamentos/tx-in', { initial: transferEnds() })
    await sheet('Editar transferência')

    expect(account()).toHaveValue('a-carteira')
    expect(destination()).toHaveValue('a-reserva')
  })

  it('só o pendente tem "Efetivar"; efetivar fecha a folha com o aviso e a marca some', async () => {
    const { memory, user } = open('/lancamentos/tx-bill', { initial: [bill()] })
    await sheet('Editar lançamento')

    await user.click(screen.getByRole('button', { name: 'Efetivar' }))

    expect(await screen.findByText('Lançamento efetivado.')).toBeInTheDocument()
    expect(memory.store[0].status).toBe('CLEARED')
    expect(screen.queryByText('Pendente')).toBeNull()
  })

  it('um lançamento efetivado não tem "Efetivar"', async () => {
    open('/lancamentos/tx-bill', { initial: [bill({ status: 'CLEARED' })] })
    await sheet('Editar lançamento')

    expect(screen.queryByRole('button', { name: 'Efetivar' })).toBeNull()
  })

  it('excluir pede confirmação: cancelar não exclui, confirmar exclui e avisa', async () => {
    const { memory, user } = open('/lancamentos/tx-lunch', { initial: [lunch()] })
    await sheet('Editar lançamento')

    await user.click(screen.getByRole('button', { name: 'Excluir' }))
    const confirm = await screen.findByRole('dialog', { name: 'Excluir este lançamento?' })
    await user.click(within(confirm).getByRole('button', { name: 'Cancelar' }))
    expect(memory.store).toHaveLength(1)

    await user.click(screen.getByRole('button', { name: 'Excluir' }))
    await user.click(within(await screen.findByRole('dialog', { name: 'Excluir este lançamento?' })).getByRole('button', { name: 'Excluir' }))

    expect(await screen.findByText('Lançamento excluído.')).toBeInTheDocument()
    expect(memory.store).toHaveLength(0)
  })

  it('um lançamento que não existe volta à lista com o aviso', async () => {
    open('/lancamentos/nao-existe', { initial: [lunch()] })

    expect(await screen.findByText(FAILURE_MESSAGES.notFound)).toBeInTheDocument()
    expect(screen.getByTestId('location').textContent).toBe('/lancamentos')
  })
})

describe('a compra parcelada na folha de lançar', () => {
  const PLAN = wsRoute('POST', WID, '/installment-purchases')
  const planBody = (api: Ctx['api']) => api.callsTo(PLAN)[0].body as Record<string, unknown>

  it('só a despesa oferece "Parcelar a compra"; marcar muda os rótulos e mostra a divisão; trocar de tipo desmarca', async () => {
    const { user } = open('/lancamentos/novo')
    await sheet('Novo lançamento')
    expect(screen.getByRole('checkbox', { name: 'Parcelar a compra' })).not.toBeChecked()

    await user.type(amount(), '1.000,00')
    await user.click(screen.getByRole('checkbox', { name: 'Parcelar a compra' }))
    await user.clear(screen.getByLabelText('Número de parcelas'))
    await user.type(screen.getByLabelText('Número de parcelas'), '3')

    expect(screen.getByLabelText('Valor total')).toBeInTheDocument()
    expect(screen.getByLabelText('Data da primeira parcela')).toBeInTheDocument()
    expect(screen.getByLabelText('Primeira parcela pendente (ainda não aconteceu)', { exact: false })).toBeInTheDocument()
    expect(screen.getByText(/3 parcelas: 1 de R\$\s*333,34 e 2 de R\$\s*333,33\./)).toBeInTheDocument()

    await user.click(radio('Receita'))
    expect(screen.queryByRole('checkbox', { name: 'Parcelar a compra' })).toBeNull()
    await user.click(radio('Despesa'))
    expect(screen.getByRole('checkbox', { name: 'Parcelar a compra' })).not.toBeChecked() // trocar de tipo desmarcou
  })

  it('cria o plano: total em centavos, parcelas, primeira data, categoria; fecha com o aviso', async () => {
    const { api, user } = open('/lancamentos/novo')
    await sheet('Novo lançamento')
    api.on(PLAN, ok(makeInstallmentPlan([makeTransaction({ id: 'p1', amountCents: -33334 })]), 201))

    await user.type(amount(), '1.000,00')
    await user.click(screen.getByRole('checkbox', { name: 'Parcelar a compra' }))
    await user.clear(screen.getByLabelText('Número de parcelas'))
    await user.type(screen.getByLabelText('Número de parcelas'), '3')
    await user.type(description(), '  Geladeira  ')
    await user.selectOptions(account(), 'a-carteira')
    await user.click(radio('Alimentação › Mercado'))
    await user.click(screen.getByRole('checkbox', { name: /Primeira parcela pendente/ }))
    await user.click(save())

    expect(await screen.findByText('Compra parcelada em 3 vezes.')).toBeInTheDocument()
    expect(planBody(api)).toEqual({ accountId: 'a-carteira', categoryId: 'c-mercado', totalCents: 100000, installmentCount: 3, firstOccurredOn: TODAY, description: 'Geladeira', firstInstallmentStatus: 'PENDING' })
    expect(api.callsTo(POST)).toHaveLength(0) // não é um lançamento comum
  })

  it('pede um número de parcelas de 2 a 48 e um centavo por parcela, e nada é enviado', async () => {
    const { api, user } = open('/lancamentos/novo')
    await sheet('Novo lançamento')
    await fillExpense(user)
    await user.click(screen.getByRole('checkbox', { name: 'Parcelar a compra' }))
    const count = screen.getByLabelText('Número de parcelas')

    await user.clear(count)
    await user.type(count, '49')
    await user.click(save())
    expect(await screen.findByText('Informe de 2 a 48 parcelas.')).toBeInTheDocument()
    expect(count).toHaveFocus()

    await user.clear(count)
    await user.type(count, '48')
    const total = screen.getByLabelText('Valor total')
    await user.clear(total)
    await user.type(total, '0,10')
    await user.click(save())
    expect(await screen.findByText(/pelo menos 1 centavo por parcela/)).toBeInTheDocument()
    expect(api.callsTo(PLAN)).toHaveLength(0)
  })

  it('uma recusa da API do plano aparece no campo certo e os valores ficam', async () => {
    const { api, user } = open('/lancamentos/novo')
    await sheet('Novo lançamento')
    api.on(PLAN, problem(400, 'Requisição inválida', { errors: [{ field: 'installmentCount', message: 'deve ser de 2 a 48' }] }))
    await fillExpense(user)
    await user.click(screen.getByRole('checkbox', { name: 'Parcelar a compra' }))

    await user.click(save())

    expect(await screen.findByText('deve ser de 2 a 48')).toBeInTheDocument()
    expect(description()).toHaveValue('Almoço')
  })
})

describe('o lançamento que nasceu de uma recorrência', () => {
  it('a folha oferece "Ver recorrência", que leva à recorrência', async () => {
    open('/lancamentos/tx-rec', { initial: [makeTransaction({ id: 'tx-rec', accountId: 'a-carteira', categoryId: 'c-mercado', amountCents: -5000, description: 'Aluguel', recurrenceId: 'r-1', occurrenceNumber: 2 })] })
    await sheet('Editar lançamento')

    expect(screen.getByRole('link', { name: 'Ver recorrência' })).toHaveAttribute('href', '/recorrencias/r-1')
  })
})

describe('o plano de uma compra parcelada', () => {
  const parcela = (n: number, status: 'CLEARED' | 'PENDING') =>
    makeTransaction({ id: `p${n}`, accountId: 'a-carteira', categoryId: 'c-mercado', amountCents: -33333, description: `Geladeira (${n}/3)`, occurredOn: `2026-1${n}-10`, status, installmentGroupId: 'g-plano', installmentNumber: n, installmentCount: 3 })
  const parcelas = () => [parcela(1, 'CLEARED'), parcela(2, 'PENDING'), parcela(3, 'PENDING')]
  const PLAN_GET = wsRoute('GET', WID, '/installment-purchases/g-plano')
  const CANCEL = wsRoute('POST', WID, '/installment-purchases/g-plano/cancel')

  function openPlan(role: Role = 'OWNER') {
    const ctx = open('/lancamentos/p2', { role, initial: parcelas() })
    ctx.api.on(PLAN_GET, ok(makeInstallmentPlan(parcelas(), { installmentCount: 3 })))
    return ctx
  }

  it('mostra o total, o efetivado, o pendente e cada parcela com o estado em palavras', async () => {
    openPlan()
    await sheet('Editar lançamento')

    const plan = await screen.findByRole('region', { name: 'Compra parcelada' })
    expect(plan).toHaveTextContent(/Geladeira: 3 parcelas\. Total R\$\s*999,99 · efetivado R\$\s*333,33 · pendente R\$\s*666,66/)
    const items = within(screen.getByRole('list', { name: 'Parcelas' })).getAllByRole('listitem')
    expect(items).toHaveLength(3)
    expect(items[0]).toHaveTextContent(/1\/3.*Efetivada/)
    expect(items[1]).toHaveTextContent(/2\/3.*Pendente/)
  })

  it('cancelar pede confirmação e exclui só as pendentes: avisa quantas e atualiza a lista', async () => {
    const { api, user } = openPlan()
    api.on(CANCEL, ok({ cancelled: 2 }))
    await sheet('Editar lançamento')

    await user.click(await screen.findByRole('button', { name: 'Cancelar parcelas pendentes' }))
    const confirm = await screen.findByRole('dialog', { name: 'Cancelar as parcelas pendentes?' })
    expect(confirm).toHaveTextContent('As 2 parcelas pendentes serão excluídas. As efetivadas continuam.')
    await user.click(within(confirm).getByRole('button', { name: 'Voltar' }))
    expect(api.callsTo(CANCEL)).toHaveLength(0)

    await user.click(screen.getByRole('button', { name: 'Cancelar parcelas pendentes' }))
    await user.click(within(await screen.findByRole('dialog', { name: 'Cancelar as parcelas pendentes?' })).getByRole('button', { name: 'Cancelar parcelas' }))

    expect(await screen.findByText('2 parcelas canceladas.')).toBeInTheDocument()
    expect(api.callsTo(CANCEL)).toHaveLength(1)
    expect(screen.getByTestId('location').textContent).toBe('/lancamentos')
  })

  it('sem pendentes não há o que cancelar; o leitor vê o plano e nenhuma ação', async () => {
    const todas = () => [parcela(1, 'CLEARED'), parcela(2, 'CLEARED'), parcela(3, 'CLEARED')]
    const done = open('/lancamentos/p2', { initial: todas() })
    done.api.on(PLAN_GET, ok(makeInstallmentPlan(todas(), { installmentCount: 3 })))
    await screen.findByRole('region', { name: 'Compra parcelada' })
    expect(screen.queryByRole('button', { name: 'Cancelar parcelas pendentes' })).toBeNull()
    done.unmount()

    openPlan('VIEWER')
    expect(await screen.findByRole('region', { name: 'Compra parcelada' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancelar parcelas pendentes' })).toBeNull()
  })

  it('um lançamento comum não mostra plano', async () => {
    open('/lancamentos/tx-lunch', { initial: [lunch()] })
    await sheet('Editar lançamento')
    expect(screen.queryByRole('region', { name: 'Compra parcelada' })).toBeNull()
  })

  it('falha ao carregar o plano: aviso com "Tentar de novo", e o resto da folha segue utilizável', async () => {
    const { api } = openPlan()
    api.on(PLAN_GET, networkError())
    await sheet('Editar lançamento')

    expect(await screen.findByRole('button', { name: 'Tentar de novo' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Salvar' })).toBeInTheDocument()
  })
})

