import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Account, CategoryTree, Transaction } from '../api/types'
import { todayISO } from '../dates/dates'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, ok, problem, wsRoute, type Handler } from '../test/fakeApi'
import { installTransactions } from '../test/fakeTransactions'
import { WID, makeAccount, makeCategory, makeCategoryTree, makeMe } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const TODAY = todayISO()
const carteira = makeAccount({ id: 'a-carteira', name: 'Carteira' })
const reserva = makeAccount({ id: 'a-reserva', name: 'Reserva' })
const antiga = makeAccount({ id: 'a-antiga', name: 'Antiga', archived: true })
const accounts: Account[] = [carteira, reserva, antiga]
const mercado = makeCategory({ id: 'c-mercado', name: 'Mercado' })
const alimentacao: CategoryTree = makeCategoryTree({ id: 'c-alim', name: 'Alimentação', kind: 'EXPENSE' }, [mercado, makeCategory({ id: 'c-velha-sub', name: 'Velha', archived: true })])
const salario = makeCategoryTree({ id: 'c-sal', name: 'Salário', kind: 'INCOME' })
const velha = makeCategoryTree({ id: 'c-velha', name: 'Antiga', kind: 'EXPENSE', archived: true })
const categories = [alimentacao, salario, velha]

const POST = wsRoute('POST', WID, '/transactions')

function open(role: 'OWNER' | 'EDITOR' | 'VIEWER' = 'OWNER', post?: Handler) {
  const api = fakeApi({
    'GET /api/v1/me': ok(makeMe(role)),
    [wsRoute('GET', WID, '/accounts')]: ok(accounts),
    [wsRoute('GET', WID, '/categories')]: ok(categories),
  })
  const memory = installTransactions(api, [], { workspaceId: WID, categories })
  if (post) api.on(POST, post)
  return { api, memory, ...renderApp(['/lancamentos/novo']) }
}
type Ctx = ReturnType<typeof open>

const ready = () => screen.findByRole('heading', { name: 'Novo lançamento' }).then(() => screen.findByLabelText('Valor'))
const amount = () => screen.getByLabelText('Valor')
const date = () => screen.getByLabelText('Data')
const description = () => screen.getByLabelText('Descrição')
const account = () => screen.getByLabelText(/^(Conta|De)$/)
const destination = () => screen.getByLabelText('Para')
const category = () => screen.getByLabelText('Categoria')
const notes = () => screen.getByLabelText(/^Observações/)
const save = () => screen.getByRole('button', { name: /^(Registrar|Salvando…)$/ })
const kind = (name: string) => screen.getByRole('radio', { name })
const body = (api: Ctx['api']) => api.callsTo(POST)[0].body as Record<string, unknown>

async function fillExpense(user: Ctx['user']) {
  await user.type(amount(), '35,90')
  await user.type(description(), 'Almoço')
  await user.selectOptions(account(), 'a-carteira')
  await user.selectOptions(category(), 'c-mercado')
}

describe('registrar uma despesa ou uma receita', () => {
  it('abre com despesa, a data de hoje e nada mais preenchido', async () => {
    open()
    await ready()

    expect(kind('Despesa')).toBeChecked()
    expect(amount()).toHaveValue('')
    expect(date()).toHaveValue(TODAY)
    expect(description()).toHaveValue('')
    expect(account()).toHaveValue('')
    expect(category()).toHaveValue('')
    expect(screen.getByRole('checkbox', { name: 'Pendente (ainda não aconteceu)' })).not.toBeChecked()
    expect(screen.getByRole('link', { name: 'Cancelar' })).toHaveAttribute('href', '/lancamentos')
  })

  it('oferece só as contas ativas e as categorias ativas do tipo, as subcategorias sob a principal', async () => {
    open()
    await ready()

    expect(Array.from(account().querySelectorAll('option')).map((o) => o.textContent)).toEqual(['Escolha a conta', 'Carteira', 'Reserva'])
    expect(Array.from(category().querySelectorAll('option')).map((o) => o.textContent)).toEqual(['Escolha a categoria', 'Alimentação', 'Alimentação › Mercado'])
  })

  it('registra a despesa em centavos inteiros positivos, volta à lista com o aviso e o lançamento aparece com o sinal', async () => {
    const { api, memory, user } = open()
    await ready()

    await user.type(amount(), '1.234,56')
    await user.type(description(), '  Almoço  ')
    await user.selectOptions(account(), 'a-carteira')
    await user.selectOptions(category(), 'c-mercado')
    await user.click(save())

    expect(await screen.findByText('Lançamento registrado.')).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: /Almoço/ })).toHaveTextContent(/−R\$\s*1\.234,56/)
    expect(screen.getByTestId('location')).toHaveTextContent('/lancamentos')
    expect(body(api)).toEqual({ type: 'EXPENSE', accountId: 'a-carteira', amountCents: 123456, occurredOn: TODAY, description: 'Almoço', categoryId: 'c-mercado' })
    expect(memory.store).toHaveLength(1)
    expect(memory.store[0].amountCents).toBe(-123456)
  })

  it('a receita oferece as categorias de receita e entra positiva', async () => {
    const { api, user } = open()
    await ready()

    await user.click(kind('Receita'))
    expect(Array.from(category().querySelectorAll('option')).map((o) => o.textContent)).toEqual(['Escolha a categoria', 'Salário'])
    await user.type(amount(), '5.000,00')
    await user.type(description(), 'Salário de novembro')
    await user.selectOptions(account(), 'a-reserva')
    await user.selectOptions(category(), 'c-sal')
    await user.click(save())

    expect(await screen.findByRole('button', { name: /Salário de novembro/ })).toHaveTextContent(/\+R\$\s*5\.000,00/)
    expect(body(api)).toMatchObject({ type: 'INCOME', amountCents: 500000, categoryId: 'c-sal' })
  })

  it('o editor também registra', async () => {
    const { api, user } = open('EDITOR')
    await ready()

    await fillExpense(user)
    await user.click(save())

    expect(await screen.findByText('Lançamento registrado.')).toBeInTheDocument()
    expect(api.callsTo(POST)).toHaveLength(1)
  })

  it('trocar de despesa para receita descarta a categoria que não serve ao novo tipo', async () => {
    const { user } = open()
    await ready()
    await user.selectOptions(category(), 'c-mercado')

    await user.click(kind('Receita'))

    expect(category()).toHaveValue('')
  })

  it('a data pode ser outra (inclusive futura), as observações vão sem os espaços das pontas e "Pendente" envia o estado', async () => {
    const { api, user } = open()
    await ready()
    await fillExpense(user)

    fireEvent.change(date(), { target: { value: '2099-06-15' } })
    await user.type(notes(), '  paguei no pix  ')
    await user.click(screen.getByRole('checkbox', { name: 'Pendente (ainda não aconteceu)' }))
    await user.click(save())

    await screen.findByText('Lançamento registrado.')
    expect(body(api)).toMatchObject({ occurredOn: '2099-06-15', notes: 'paguei no pix', status: 'PENDING' })
    expect(await screen.findByRole('button', { name: /Almoço/ })).toHaveTextContent('Pendente')
  })

  it('efetivado (a caixa desmarcada) não envia o estado, e sem observações não envia observações', async () => {
    const { api, user } = open()
    await ready()
    await fillExpense(user)

    await user.click(save())

    await screen.findByText('Lançamento registrado.')
    expect('status' in body(api)).toBe(false)
    expect('notes' in body(api)).toBe(false)
  })

  it('cancelar volta à lista sem registrar nada', async () => {
    const { api, user } = open()
    await ready()
    await user.type(description(), 'Esquecido')

    await user.click(screen.getByRole('link', { name: 'Cancelar' }))

    expect(await screen.findByRole('heading', { name: 'Lançamentos' })).toBeInTheDocument()
    expect(api.callsTo(POST)).toHaveLength(0)
  })
})

describe('as regras no campo, antes de enviar', () => {
  it('vazio: valor, descrição, conta e categoria pedem; o foco vai ao primeiro (o valor) e nada é enviado', async () => {
    const { api, user } = open()
    await ready()

    await user.click(save())

    expect(amount()).toHaveAccessibleDescription('Informe o valor.')
    expect(description()).toHaveAccessibleDescription('Informe a descrição.')
    expect(account()).toHaveAccessibleDescription('Escolha a conta.')
    expect(category()).toHaveAccessibleDescription('Escolha a categoria.')
    expect(amount()).toHaveFocus()
    expect(api.callsTo(POST)).toHaveLength(0)
  })

  it.each([
    ['0', 'O valor deve ser maior que zero.'],
    ['-5', 'O valor deve ser maior que zero.'],
    ['abc', 'Informe um valor em reais, como 1.234,56.'],
    ['12.5', 'Use vírgula para os centavos, por exemplo 12,50.'],
    ['10.000.000.000,01', 'O valor máximo é R$ 10.000.000.000,00.'],
  ])('o valor %s é recusado no campo', async (typed, message) => {
    const { api, user } = open()
    await ready()
    await fillExpense(user)
    await user.clear(amount())
    await user.type(amount(), typed)

    await user.click(save())

    expect(amount()).toHaveAccessibleDescription(message)
    expect(amount()).toHaveFocus()
    expect(api.callsTo(POST)).toHaveLength(0)
  })

  it('o valor no limite exato é aceito', async () => {
    const { api, user } = open()
    await ready()
    await fillExpense(user)
    await user.clear(amount())
    await user.type(amount(), '10.000.000.000,00')

    await user.click(save())

    await screen.findByText('Lançamento registrado.')
    expect(body(api).amountCents).toBe(1_000_000_000_000)
  })

  it('a descrição de 201 caracteres e as observações de 1.001 são recusadas', async () => {
    const { api, user } = open()
    await ready()
    await fillExpense(user)
    await user.click(description())
    await user.paste('a'.repeat(200)) // (já há "Almoço": passa de 200)
    await user.click(notes())
    await user.paste('b'.repeat(1001))

    await user.click(save())

    expect(description()).toHaveAccessibleDescription('Use no máximo 200 caracteres.')
    expect(notes()).toHaveAccessibleDescription(/Use no máximo 1\.000 caracteres\./)
    expect(api.callsTo(POST)).toHaveLength(0)
  })

  it('a data apagada pede a data; um ano fora de 2000 a 2100 é recusado', async () => {
    const { api, user } = open()
    await ready()
    await fillExpense(user)

    fireEvent.change(date(), { target: { value: '' } })
    await user.click(save())
    expect(date()).toHaveAccessibleDescription('Informe a data.')
    expect(date()).toHaveFocus()

    fireEvent.change(date(), { target: { value: '1999-12-31' } })
    await user.click(save())
    expect(date()).toHaveAccessibleDescription('Use um ano de 2000 a 2100.')
    expect(api.callsTo(POST)).toHaveLength(0)
  })

  it('o foco vai ao primeiro campo com erro, mesmo que não seja o valor', async () => {
    const { user } = open()
    await ready()
    await user.type(amount(), '10')
    await user.type(description(), 'x')
    await user.selectOptions(account(), 'a-carteira')

    await user.click(save())

    expect(category()).toHaveFocus()
  })

  it('consertar o erro e enviar de novo funciona', async () => {
    const { api, user } = open()
    await ready()
    await user.click(save())
    await fillExpense(user)

    await user.click(save())

    await screen.findByText('Lançamento registrado.')
    expect(api.callsTo(POST)).toHaveLength(1)
  })
})

describe('registrar uma transferência', () => {
  it('mostra "De" e "Para" no lugar da conta e da categoria', async () => {
    const { user } = open()
    await ready()

    await user.click(kind('Transferência'))

    expect(screen.getByLabelText('De')).toBeInTheDocument()
    expect(destination()).toBeInTheDocument()
    expect(screen.queryByLabelText('Categoria')).toBeNull()
    expect(screen.queryByLabelText('Conta')).toBeNull()
  })

  it('cria as duas pontas: a saída na origem e a entrada no destino, sem categoria', async () => {
    const { api, memory, user } = open()
    await ready()
    await user.click(kind('Transferência'))

    await user.type(amount(), '100,00')
    await user.type(description(), 'Para a reserva')
    await user.selectOptions(screen.getByLabelText('De'), 'a-carteira')
    await user.selectOptions(destination(), 'a-reserva')
    await user.click(save())

    expect(await screen.findByText('Lançamento registrado.')).toBeInTheDocument()
    expect(body(api)).toEqual({ type: 'TRANSFER', accountId: 'a-carteira', destinationAccountId: 'a-reserva', amountCents: 10000, occurredOn: TODAY, description: 'Para a reserva' })
    expect('categoryId' in body(api)).toBe(false)
    expect(memory.store).toHaveLength(2)
    expect(await screen.findAllByRole('button', { name: /Para a reserva/ })).toHaveLength(2)
  })

  it('origem igual ao destino é recusada no campo do destino', async () => {
    const { api, user } = open()
    await ready()
    await user.click(kind('Transferência'))
    await user.type(amount(), '10')
    await user.type(description(), 'x')
    await user.selectOptions(screen.getByLabelText('De'), 'a-carteira')
    await user.selectOptions(destination(), 'a-carteira')

    await user.click(save())

    expect(destination()).toHaveAccessibleDescription('A origem e o destino precisam ser diferentes.')
    expect(destination()).toHaveFocus()
    expect(api.callsTo(POST)).toHaveLength(0)
  })

  it('origem e destino pedem escolha', async () => {
    const { user } = open()
    await ready()
    await user.click(kind('Transferência'))

    await user.click(save())

    expect(screen.getByLabelText('De')).toHaveAccessibleDescription('Escolha a conta de origem.')
    expect(destination()).toHaveAccessibleDescription('Escolha a conta de destino.')
    expect(screen.queryByText('Escolha a categoria.')).toBeNull()
  })

  it('trocar de transferência para despesa descarta o destino e volta a pedir a categoria', async () => {
    const { api, user } = open()
    await ready()
    await user.click(kind('Transferência'))
    await user.type(amount(), '10')
    await user.type(description(), 'Mudou de ideia')
    await user.selectOptions(screen.getByLabelText('De'), 'a-carteira')
    await user.selectOptions(destination(), 'a-reserva')

    await user.click(kind('Despesa'))
    expect(screen.queryByLabelText('Para')).toBeNull()
    expect(category()).toHaveValue('')
    await user.click(save())

    expect(category()).toHaveAccessibleDescription('Escolha a categoria.')
    expect(api.callsTo(POST)).toHaveLength(0)
    await user.selectOptions(category(), 'c-mercado')
    await user.click(save())
    await screen.findByText('Lançamento registrado.')
    expect(body(api)).toEqual({ type: 'EXPENSE', accountId: 'a-carteira', amountCents: 1000, occurredOn: TODAY, description: 'Mudou de ideia', categoryId: 'c-mercado' })
  })
})

describe('quando a API recusa', () => {
  it('400 com erro de campo: vai ao campo certo; o que não é da tela vira o aviso geral', async () => {
    const { user } = open('OWNER', problem(400, 'Requisição inválida', { errors: [{ field: 'amountCents', message: 'fora do limite' }, { field: 'currency', message: 'moeda inválida' }] }))
    await ready()
    await fillExpense(user)

    await user.click(save())

    await waitFor(() => expect(amount()).toHaveAccessibleDescription('fora do limite'))
    expect(screen.getByRole('alert')).toHaveTextContent('moeda inválida')
    expect(amount()).toHaveFocus()
    expect(description()).toHaveValue('Almoço')
  })

  it('uma regra de negócio da API (por exemplo conta arquivada) aparece como a API disse, e os valores ficam', async () => {
    const { user } = open('OWNER', problem(409, 'Conflito', { detail: 'A conta está arquivada' }))
    await ready()
    await fillExpense(user)

    await user.click(save())

    expect(await screen.findByRole('alert')).toHaveTextContent('A conta está arquivada')
    expect(amount()).toHaveValue('35,90')
    expect(account()).toHaveValue('a-carteira')
  })

  it('403: mensagem de permissão, e continua no formulário', async () => {
    const { user } = open('OWNER', problem(403, 'Proibido'))
    await ready()
    await fillExpense(user)

    await user.click(save())

    expect(await screen.findByRole('alert')).toHaveTextContent('Você não tem permissão para alterar isto neste workspace.')
    expect(description()).toHaveValue('Almoço')
  })

  it('servidor fora do ar: avisa com "Tentar de novo", mantém os valores e envia outra vez', async () => {
    const { api, user } = open('OWNER', networkError())
    await ready()
    await fillExpense(user)
    await user.click(save())
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível falar com o servidor. Tente de novo.')

    api.on(POST, ok({ id: 'novo' }, 201))
    await user.click(screen.getByRole('button', { name: 'Tentar de novo' }))

    expect(await screen.findByText('Lançamento registrado.')).toBeInTheDocument()
    expect(api.callsTo(POST)).toHaveLength(2)
  })

  it('o envio duplicado (cliques e Enter seguidos) chama a API uma vez só e mostra que está salvando', async () => {
    let release!: () => void
    const wait = new Promise<void>((resolve) => {
      release = resolve
    })
    const { api, user } = open('OWNER', async () => (await wait, ok({ id: 'novo' } as Transaction, 201)))
    await ready()
    await fillExpense(user)

    await user.click(save())
    await user.click(save())
    await user.keyboard('{Enter}')

    expect(screen.getByRole('button', { name: 'Salvando…' })).toBeDisabled()
    await act(async () => release())
    expect(await screen.findByText('Lançamento registrado.')).toBeInTheDocument()
    expect(api.callsTo(POST)).toHaveLength(1)
  })
})

describe('quem só consulta e a acessibilidade', () => {
  it('o leitor vê o aviso de papel e a volta, nunca o formulário', async () => {
    const { api } = open('VIEWER')

    expect(await screen.findByRole('heading', { name: 'Somente consulta' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Voltar aos lançamentos' })).toHaveAttribute('href', '/lancamentos')
    expect(screen.queryByLabelText('Valor')).toBeNull()
    expect(api.callsTo(wsRoute('GET', WID, '/accounts'))).toHaveLength(0)
  })

  it('falha ao carregar as contas e categorias: aviso com "Tentar de novo"', async () => {
    const { api, user } = open()
    api.on(wsRoute('GET', WID, '/categories'), networkError())
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar as contas e categorias. Tente de novo.')

    api.on(wsRoute('GET', WID, '/categories'), ok(categories))
    await user.click(screen.getByRole('button', { name: 'Tentar de novo' }))

    expect(await ready()).toBeInTheDocument()
  })

  it('passa no axe: em repouso, como transferência e com erros', async () => {
    const { container, user } = open()
    await ready()
    await expectNoSeriousViolations(container)

    await user.click(kind('Transferência'))
    await expectNoSeriousViolations(container)

    await user.click(save())
    await waitFor(() => expect(amount()).toHaveAttribute('aria-invalid', 'true'))
    await expectNoSeriousViolations(container)
  })
})
