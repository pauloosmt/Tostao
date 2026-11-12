import { act, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Recurrence } from '../api/types'
import { expectNoSeriousViolations } from '../test/axe'
import { todayISO } from '../dates/dates'
import { fakeApi, networkError, ok, problem, wsRoute } from '../test/fakeApi'
import { WID, makeAccount, makeCategory, makeCategoryTree, makeMe, makeRecurrence, makeRecurrenceDetail, makeTransaction } from '../test/fixtures'
import { renderApp } from '../test/renderApp'
import { setViewportWidth } from '../test/media'
import { sortRecurrences } from './sortRecurrences'

const carteira = makeAccount({ id: 'a-carteira', name: 'Carteira' })
const reserva = makeAccount({ id: 'a-reserva', name: 'Reserva' })
const accounts = [carteira, reserva]
const moradia = makeCategoryTree({ id: 'c-mor', name: 'Moradia', kind: 'EXPENSE', color: '#4fb3b1', icon: 'house' }, [makeCategory({ id: 'c-alug', name: 'Aluguel', parentId: 'c-mor' })])
const salario = makeCategoryTree({ id: 'c-sal', name: 'Salário', kind: 'INCOME', color: '#e0a93a', icon: 'briefcase' })
const categories = [moradia, salario]

const aluguel = makeRecurrence({ id: 'r-aluguel', description: 'Aluguel', categoryId: 'c-mor', amountCents: 120000, nextOccurrenceOn: '2026-12-10', autoClear: true })
const pagamento = makeRecurrence({ id: 'r-pag', description: 'Salário', type: 'INCOME', categoryId: 'c-sal', amountCents: 500000, frequency: 'MONTHLY', nextOccurrenceOn: '2026-12-05' })
const reservaMensal = makeRecurrence({ id: 'r-res', description: 'Reserva', type: 'TRANSFER', categoryId: null, destinationAccountId: 'a-reserva', amountCents: 20000, frequency: 'WEEKLY', nextOccurrenceOn: null })
const velha = makeRecurrence({ id: 'r-velha', description: 'Academia', categoryId: 'c-mor', ended: true, nextOccurrenceOn: null })

function open(list: Recurrence[] | unknown, role: 'OWNER' | 'EDITOR' | 'VIEWER' = 'OWNER', path = '/recorrencias') {
  const api = fakeApi({
    'GET /api/v1/me': ok(makeMe(role)),
    [wsRoute('GET', WID, '/recurrences')]: Array.isArray(list) ? ok(list) : (list as never),
    [wsRoute('GET', WID, '/accounts')]: ok(accounts),
    [wsRoute('GET', WID, '/categories')]: ok(categories),
  })
  return { api, ...renderApp([path]) }
}
const rows = () => within(screen.getByRole('list', { name: 'Recorrências' })).getAllByRole('listitem')

describe('a lista de recorrências', () => {
  it('cada uma com valor e sinal, frequência, conta, categoria, próxima data e selos; ativas antes das encerradas', async () => {
    const { container } = open([velha, reservaMensal, pagamento, aluguel])

    await screen.findByRole('list', { name: 'Recorrências' })
    expect(rows().map((row) => row.querySelector('.tx-row__title')?.textContent)).toEqual(['Salário', 'Aluguel', 'Reserva', 'Academia'])
    expect(rows()[0]).toHaveTextContent(/\+R\$\s*5\.000,00/)
    expect(rows()[0]).toHaveTextContent(/Todo mês · Próxima: 05\/12\/2026/)
    expect(rows()[1]).toHaveTextContent(/−R\$\s*1\.200,00.*Efetiva sozinha|Efetiva sozinha.*−R\$\s*1\.200,00/)
    expect(rows()[2]).toHaveTextContent(/Carteira · Para Reserva · Toda semana · Sem próximas ocorrências/) // a transferência vai de uma conta à outra
    expect(rows()[3]).toHaveTextContent(/Encerrada/)
    expect(screen.getByRole('link', { name: 'Nova recorrência' })).toHaveAttribute('href', '/recorrencias/nova')
    await expectNoSeriousViolations(container)
  })

  it('sem recorrências explica; o leitor não tem o botão; falha avisa com "Tentar de novo"', async () => {
    const empty = open([])
    expect(await screen.findByText('Nenhuma recorrência ainda')).toBeInTheDocument()
    empty.unmount()

    open([aluguel], 'VIEWER')
    await screen.findByRole('list', { name: 'Recorrências' })
    expect(screen.queryByRole('link', { name: 'Nova recorrência' })).toBeNull()
    document.body.innerHTML = ''

    open(networkError())
    expect(await screen.findByRole('button', { name: 'Tentar de novo' })).toBeInTheDocument()
  })

  it('o menu lateral tem "Recorrências"; no celular o atalho fica em Contas', async () => {
    setViewportWidth(1280)
    const wide = open([aluguel])
    await screen.findByRole('list', { name: 'Recorrências' })
    expect(screen.getByRole('link', { name: 'Recorrências' })).toHaveAttribute('aria-current', 'page')
    wide.unmount()

    setViewportWidth(500)
    fakeApi({
      'GET /api/v1/me': ok(makeMe('OWNER')),
      [wsRoute('GET', WID, '/accounts')]: ok(accounts),
      [wsRoute('GET', WID, '/reports/balance')]: ok({ accounts: [], totalBalanceCents: 0 }),
    })
    renderApp(['/contas'])
    expect(await screen.findByRole('link', { name: 'Recorrências' })).toHaveAttribute('href', '/recorrencias')
  })
})

describe('a ordem', () => {
  it('ativas antes das encerradas; entre as ativas, a que ocorre antes; sem próxima por último; depois pela descrição', () => {
    const a = makeRecurrence({ description: 'B', nextOccurrenceOn: '2026-12-01' })
    const b = makeRecurrence({ description: 'A', nextOccurrenceOn: '2026-12-01' })
    const c = makeRecurrence({ description: 'C', nextOccurrenceOn: null })
    const d = makeRecurrence({ description: 'D', ended: true })
    const e = makeRecurrence({ description: 'E', nextOccurrenceOn: '2026-11-20' })

    expect(sortRecurrences([d, c, a, e, b]).map((r) => r.description)).toEqual(['E', 'A', 'B', 'C', 'D'])
  })
})

describe('criar uma recorrência na folha', () => {
  const POST = wsRoute('POST', WID, '/recurrences')
  const TODAY = todayISO()
  const created = ok(makeRecurrenceDetail([], { id: 'r-nova' }), 201)
  const sheet = () => screen.findByRole('heading', { name: 'Nova recorrência' })
  const body = (api: ReturnType<typeof open>['api']) => api.callsTo(POST)[0].body as Record<string, unknown>
  const save = () => screen.getByRole('button', { name: /^(Criar recorrência|Salvando…)$/ })

  it('abre pelo botão com despesa, todo mês e a data de hoje; cria com o valor em centavos e a lista atualiza com o aviso', async () => {
    const { api, user } = open([aluguel])
    api.on(POST, created)
    await user.click(await screen.findByRole('link', { name: 'Nova recorrência' }))
    await sheet()

    expect(screen.getByRole('radio', { name: 'Despesa' })).toBeChecked()
    expect(screen.getByRole('radio', { name: 'Todo mês' })).toBeChecked()
    expect(screen.getByLabelText('Começa em')).toHaveValue(TODAY)
    expect(screen.getByLabelText('Termina em (opcional)')).toHaveValue('')
    await user.type(screen.getByLabelText('Valor'), '1.200,00')
    await user.type(screen.getByLabelText('Descrição'), '  Aluguel novo  ')
    await user.selectOptions(screen.getByLabelText('Conta'), 'a-carteira')
    await user.click(screen.getByRole('radio', { name: 'Moradia' }))
    await user.click(screen.getByRole('checkbox', { name: 'Efetivar sozinhas na data' }))
    await user.click(save())

    expect(await screen.findByText('Recorrência criada.')).toBeInTheDocument()
    expect(body(api)).toEqual({ accountId: 'a-carteira', categoryId: 'c-mor', type: 'EXPENSE', amountCents: 120000, description: 'Aluguel novo', frequency: 'MONTHLY', startOn: TODAY, autoClear: true })
    expect(screen.queryByLabelText('Valor')).toBeNull()
    expect(api.callsTo(wsRoute('GET', WID, '/recurrences'))).toHaveLength(2) // a lista recarregou
  })

  it('a transferência pede De e Para, sem categoria; troca a frequência e o fim', async () => {
    const { api, user } = open([])
    api.on(POST, created)
    await user.click(await screen.findByRole('link', { name: 'Nova recorrência' }))
    await sheet()

    await user.click(screen.getByRole('radio', { name: 'Transferência' }))
    expect(screen.queryByRole('radiogroup', { name: 'Categoria' })).toBeNull()
    await user.click(screen.getByRole('radio', { name: 'Toda semana' }))
    await user.type(screen.getByLabelText('Valor'), '200')
    await user.type(screen.getByLabelText('Descrição'), 'Reserva')
    await user.selectOptions(screen.getByLabelText('De'), 'a-carteira')
    await user.selectOptions(screen.getByLabelText('Para'), 'a-reserva')
    await user.type(screen.getByLabelText('Termina em (opcional)'), '2099-12-31')
    await user.click(save())

    expect(await screen.findByText('Recorrência criada.')).toBeInTheDocument()
    expect(body(api)).toEqual({ accountId: 'a-carteira', destinationAccountId: 'a-reserva', type: 'TRANSFER', amountCents: 20000, description: 'Reserva', frequency: 'WEEKLY', startOn: TODAY, endOn: '2099-12-31' })
  })

  it('vazio pede o que falta com o foco no valor; fim antes do início é recusado; nada é enviado', async () => {
    const { api, user } = open([])
    await user.click(await screen.findByRole('link', { name: 'Nova recorrência' }))
    await sheet()

    await user.click(save())
    expect(await screen.findByText('Informe o valor.')).toBeInTheDocument()
    expect(screen.getByText('Informe a descrição.')).toBeInTheDocument()
    expect(screen.getByLabelText('Valor')).toHaveFocus()

    await user.type(screen.getByLabelText('Termina em (opcional)'), '2000-01-01')
    await user.click(save())
    expect(await screen.findByText('O fim não pode ser antes do início.')).toBeInTheDocument()
    expect(api.callsTo(POST)).toHaveLength(0)
  })

  it('uma recusa da API vai ao campo certo e os valores ficam; o envio duplicado chama a API uma vez só', async () => {
    let release!: () => void
    const wait = new Promise<void>((resolve) => {
      release = resolve
    })
    const { api, user } = open([])
    api.on(POST, async () => (await wait, problem(400, 'Requisição inválida', { errors: [{ field: 'startOn', message: 'deve ser uma data de até 366 dias atrás até 2100-12-31' }] })))
    await user.click(await screen.findByRole('link', { name: 'Nova recorrência' }))
    await sheet()
    await user.type(screen.getByLabelText('Valor'), '10')
    await user.type(screen.getByLabelText('Descrição'), 'Café')
    await user.selectOptions(screen.getByLabelText('Conta'), 'a-carteira')
    await user.click(screen.getByRole('radio', { name: 'Moradia' }))

    await user.dblClick(save())
    expect(screen.getByRole('button', { name: 'Salvando…' })).toBeDisabled()
    await act(async () => release())

    expect(await screen.findByText('deve ser uma data de até 366 dias atrás até 2100-12-31')).toBeInTheDocument()
    expect(api.callsTo(POST)).toHaveLength(1)
    expect(screen.getByLabelText('Descrição')).toHaveValue('Café')
  })

  it('o leitor volta à lista com o aviso, sem formulário; a folha passa no axe', async () => {
    const viewer = open([aluguel], 'VIEWER', '/recorrencias/nova')
    expect(await screen.findByText(/só para consulta/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Valor')).toBeNull()
    viewer.unmount()

    const { container } = open([], 'OWNER', '/recorrencias/nova')
    await sheet()
    await expectNoSeriousViolations(container)
  })
})

describe('os detalhes e o encerramento', () => {
  const occurrence = (n: number, status: 'CLEARED' | 'PENDING') => makeTransaction({ id: `o${n}`, recurrenceId: 'r-aluguel', occurrenceNumber: n, amountCents: -120000, occurredOn: `2026-1${n}-10`, status })
  const detail = (extra = {}) => makeRecurrenceDetail([occurrence(1, 'CLEARED'), occurrence(2, 'PENDING'), occurrence(3, 'PENDING')], { ...aluguel, ...extra })
  const GET = wsRoute('GET', WID, '/recurrences/r-aluguel')
  const END = wsRoute('POST', WID, '/recurrences/r-aluguel/end')

  function openDetail(role: 'OWNER' | 'EDITOR' | 'VIEWER' = 'OWNER', data = detail()) {
    const ctx = open([aluguel], role, '/recorrencias/r-aluguel')
    ctx.api.on(GET, ok(data))
    return ctx
  }

  it('tocar numa recorrência abre os dados e as ocorrências com o estado em palavras e os totais', async () => {
    const { api, user } = open([aluguel])
    api.on(GET, ok(detail()))
    await user.click(await screen.findByRole('button', { name: /Aluguel/ }))

    expect(await screen.findByRole('heading', { name: 'Aluguel' })).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/recorrencias/r-aluguel')
    expect(screen.getByText(/Despesa · Todo mês · começa em 10\/11\/2026 · sem fim/)).toBeInTheDocument()
    expect(screen.getAllByText(/Carteira · Moradia/).length).toBeGreaterThan(1) // na linha da lista e nos detalhes
    expect(screen.getByText(/Total R\$\s*3\.600,00 · efetivado R\$\s*1\.200,00 · pendente R\$\s*2\.400,00/)).toBeInTheDocument()
    const items = within(screen.getByRole('list', { name: 'Ocorrências da recorrência' })).getAllByRole('listitem')
    expect(items).toHaveLength(3)
    expect(items[0]).toHaveTextContent(/Efetivada/)
    expect(items[1]).toHaveTextContent(/Pendente/)
  })

  it('encerrar pede confirmação (cancelar não encerra); confirmar exclui as pendentes e avisa quantas', async () => {
    const { api, user } = openDetail()
    api.on(END, ok({ cancelled: 2 }))
    await user.click(await screen.findByRole('button', { name: 'Encerrar recorrência' }))
    const confirm = await screen.findByRole('dialog', { name: 'Encerrar esta recorrência?' })
    expect(confirm).toHaveTextContent('As 2 ocorrências pendentes serão excluídas.')
    await user.click(within(confirm).getByRole('button', { name: 'Voltar' }))
    expect(api.callsTo(END)).toHaveLength(0)

    await user.click(screen.getByRole('button', { name: 'Encerrar recorrência' }))
    await user.click(within(await screen.findByRole('dialog', { name: 'Encerrar esta recorrência?' })).getByRole('button', { name: 'Encerrar' }))

    expect(await screen.findByText('Recorrência encerrada. 2 ocorrências canceladas.')).toBeInTheDocument()
    expect(api.callsTo(END)).toHaveLength(1)
    expect(screen.getByTestId('location').textContent).toBe('/recorrencias')
    expect(api.callsTo(wsRoute('GET', WID, '/recurrences'))).toHaveLength(2) // a lista recarregou
  })

  it('a encerrada e o leitor não têm "Encerrar"; a que não existe volta à lista com o aviso', async () => {
    const ended = openDetail('OWNER', detail({ ended: true }))
    expect(await screen.findByText('Recorrência encerrada.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Encerrar recorrência' })).toBeNull()
    ended.unmount()

    const viewer = openDetail('VIEWER')
    expect(await screen.findByRole('list', { name: 'Ocorrências da recorrência' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Encerrar recorrência' })).toBeNull()
    viewer.unmount()

    const gone = open([aluguel], 'OWNER', '/recorrencias/r-aluguel')
    gone.api.on(GET, problem(404, 'Não encontrado'))
    expect(await screen.findByText('Não encontrada. A lista foi atualizada.')).toBeInTheDocument()
  })

  it('passa no axe e, sem rede, avisa com "Tentar de novo" sem derrubar a lista', async () => {
    const first = openDetail()
    await screen.findByRole('heading', { name: 'Aluguel' })
    await expectNoSeriousViolations(first.container)
    first.unmount()

    const { api } = open([aluguel], 'OWNER', '/recorrencias/r-aluguel')
    api.on(GET, networkError())
    expect(await screen.findByRole('button', { name: 'Tentar de novo' })).toBeInTheDocument()
  })
})

