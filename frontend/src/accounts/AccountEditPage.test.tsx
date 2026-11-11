import { screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Account } from '../api/types'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, ok, problem, wsRoute, type Handler } from '../test/fakeApi'
import { WID, makeAccount, makeBalanceReport, makeMe } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const carteira = makeAccount({ id: 'acc-carteira', name: 'Carteira', type: 'CASH', initialBalanceCents: 123456, balanceCents: 123456 })
const nubank = makeAccount({ id: 'acc-nubank', name: 'Nubank', type: 'CREDIT_CARD', initialBalanceCents: -5000, balanceCents: -5000, creditLimitCents: 500000, closingDay: 10, dueDay: 17 })
const antiga = makeAccount({ id: 'acc-antiga', name: 'Antiga', archived: true })

function open(
  account: Account,
  { role = 'OWNER' as 'OWNER' | 'EDITOR' | 'VIEWER', get = ok(account) as Handler, put = ok(account) as Handler } = {},
) {
  const PUT = wsRoute('PUT', WID, `/accounts/${account.id}`)
  const api = fakeApi({
    'GET /api/v1/me': ok(makeMe(role)),
    [wsRoute('GET', WID, `/accounts/${account.id}`)]: get,
    [PUT]: put,
    [wsRoute('GET', WID, '/accounts')]: ok([account]),
    [wsRoute('GET', WID, '/reports/balance')]: ok(makeBalanceReport([account])),
  })
  return { api, PUT, ...renderApp([`/contas/${account.id}`]) }
}

const name = () => screen.getByLabelText('Nome')
const type = () => screen.getByLabelText('Tipo')
const balance = () => screen.getByLabelText('Saldo inicial')
const save = () => screen.getByRole('button', { name: /^(Salvar|Salvando…)$/ })
const ready = () => screen.findByRole('heading', { name: 'Editar conta' })

/** A lista de contas, como a API a devolve: só as ativas, e as arquivadas também com `includeArchived`. */
function openList(role: 'OWNER' | 'EDITOR' | 'VIEWER', accounts: Account[]) {
  const api = fakeApi({
    'GET /api/v1/me': ok(makeMe(role)),
    [wsRoute('GET', WID, '/accounts')]: (call) => ok(call.path.includes('includeArchived=true') ? accounts : accounts.filter((a) => !a.archived)),
    [wsRoute('GET', WID, '/reports/balance')]: ok(makeBalanceReport(accounts)),
  })
  return { api, ...renderApp(['/contas']) }
}

describe('o caminho até a edição', () => {
  it('dono e editor veem "Editar" em cada conta ativa', async () => {
    for (const role of ['OWNER', 'EDITOR'] as const) {
      const view = openList(role, [carteira, nubank])
      await screen.findByRole('list', { name: 'Contas' })
      expect(screen.getByRole('link', { name: 'Editar Carteira' })).toHaveAttribute('href', '/contas/acc-carteira')
      expect(screen.getByRole('link', { name: 'Editar Nubank' })).toHaveAttribute('href', '/contas/acc-nubank')
      view.unmount()
    }
  })

  it('o leitor não vê "Editar"', async () => {
    openList('VIEWER', [carteira])
    await screen.findByRole('list', { name: 'Contas' })

    expect(screen.queryByRole('link', { name: /^Editar / })).toBeNull()
  })

  it('uma conta arquivada não tem "Editar" (só as ativas)', async () => {
    const { user } = openList('OWNER', [carteira, antiga])
    await screen.findByRole('list', { name: 'Contas' })

    await user.click(screen.getByRole('checkbox', { name: 'Mostrar arquivadas' }))

    expect(await screen.findByText('Antiga')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Editar Carteira' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Editar Antiga' })).toBeNull()
  })

  it('tocar em "Editar" abre a conta num endereço próprio', async () => {
    const { api, user } = openList('OWNER', [carteira])
    api.on(wsRoute('GET', WID, `/accounts/${carteira.id}`), ok(carteira))
    await screen.findByRole('list', { name: 'Contas' })

    await user.click(screen.getByRole('link', { name: 'Editar Carteira' }))

    expect(await ready()).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/contas/acc-carteira')
  })
})

describe('editar uma conta', () => {
  it('abre preenchida: nome, o tipo visível e não editável, e o saldo inicial no formato do campo', async () => {
    open(carteira)
    await ready()

    expect(name()).toHaveValue('Carteira')
    expect(type()).toHaveValue('CASH')
    expect(type()).toBeDisabled()
    expect(balance()).toHaveValue('1.234,56')
    expect(screen.queryByLabelText('Limite (opcional)')).toBeNull()
  })

  it('salvar envia nome e saldo (sem o tipo), volta à lista com o aviso', async () => {
    const { api, PUT, user } = open(carteira)
    await ready()

    await user.clear(name())
    await user.type(name(), '  Carteira nova  ')
    await user.clear(balance())
    await user.type(balance(), '2.000,00')
    await user.click(save())

    expect(await screen.findByText('Conta salva.')).toBeInTheDocument()
    expect(await screen.findByRole('list', { name: 'Contas' })).toBeInTheDocument()
    expect(api.callsTo(PUT)).toHaveLength(1)
    expect(api.callsTo(PUT)[0].body).toEqual({ name: 'Carteira nova', initialBalanceCents: 200000 })
    expect('type' in (api.callsTo(PUT)[0].body as object)).toBe(false)
  })

  it('o cartão abre com os dados e os reenvia; limpar um campo o tira do corpo', async () => {
    const { api, PUT, user } = open(nubank)
    await ready()
    expect(screen.getByLabelText('Limite (opcional)')).toHaveValue('5.000,00')
    expect(screen.getByLabelText('Dia de fechamento (opcional)')).toHaveValue('10')
    expect(screen.getByLabelText('Dia de vencimento (opcional)')).toHaveValue('17')

    await user.clear(screen.getByLabelText('Dia de fechamento (opcional)'))
    await user.click(save())

    await screen.findByText('Conta salva.')
    expect(api.callsTo(PUT)[0].body).toEqual({ name: 'Nubank', initialBalanceCents: -5000, creditLimitCents: 500000, dueDay: 17 })
  })

  it('as mesmas regras do cadastro valem: erro no campo, foco no primeiro, nada enviado', async () => {
    const { api, PUT, user } = open(carteira)
    await ready()
    await user.clear(name())
    await user.clear(balance())
    await user.type(balance(), 'abc')

    await user.click(save())

    expect(name()).toHaveAccessibleDescription('Informe o nome.')
    expect(balance()).toHaveAccessibleDescription('Informe um valor em reais, como 1.234,56.')
    expect(name()).toHaveFocus()
    expect(api.callsTo(PUT)).toHaveLength(0)
  })

  it('409: nome em uso aparece no campo do nome, com os outros valores mantidos', async () => {
    const { user } = open(carteira, { put: problem(409, 'Nome em uso') })
    await ready()
    await user.clear(name())
    await user.type(name(), 'Já existe')

    await user.click(save())

    await waitFor(() => expect(name()).toHaveAccessibleDescription('Já existe uma conta ativa com este nome.'))
    expect(name()).toHaveValue('Já existe')
    expect(balance()).toHaveValue('1.234,56')
  })

  it('403 e falha de rede ao salvar mostram o aviso e mantêm o formulário', async () => {
    const forbidden = open(carteira, { put: problem(403, 'Proibido') })
    await ready()
    await forbidden.user.click(save())
    expect(await screen.findByRole('alert')).toHaveTextContent('Você não tem permissão para alterar isto neste workspace.')
    forbidden.unmount()

    const offline = open(carteira, { put: networkError() })
    await ready()
    await offline.user.click(save())
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível falar com o servidor. Tente de novo.')
    expect(screen.getByRole('button', { name: 'Tentar de novo' })).toBeInTheDocument()
  })

  it('404 ao salvar (a conta deixou de existir): volta à lista atualizada, com o aviso', async () => {
    const { user } = open(carteira, { put: problem(404, 'Não encontrada') })
    await ready()

    await user.click(save())

    expect(await screen.findByText('Não encontrada. A lista foi atualizada.')).toBeInTheDocument()
    expect(await screen.findByRole('list', { name: 'Contas' })).toBeInTheDocument()
  })

  it('cancelar volta à lista sem salvar', async () => {
    const { api, PUT, user } = open(carteira)
    await ready()
    await user.type(name(), ' mudou')

    await user.click(screen.getByRole('link', { name: 'Cancelar' }))

    expect(await screen.findByRole('heading', { name: 'Contas' })).toBeInTheDocument()
    expect(api.callsTo(PUT)).toHaveLength(0)
  })
})

describe('os outros casos de /contas/:id', () => {
  it('uma conta arquivada pede para restaurar antes, sem formulário de edição', async () => {
    open(antiga)

    expect(await screen.findByText('Conta arquivada')).toBeInTheDocument()
    expect(screen.getByText(/Restaure a conta antes de editá-la/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Nome')).toBeNull()
    expect(screen.getByRole('link', { name: 'Voltar às contas' })).toHaveAttribute('href', '/contas')
  })

  it('uma conta que não existe (ou é de outro workspace) é "não encontrada", com a volta', async () => {
    open(carteira, { get: problem(404, 'Não encontrada') })

    expect(await screen.findByRole('heading', { name: 'Conta não encontrada' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Voltar às contas' })).toHaveAttribute('href', '/contas')
    expect(screen.queryByLabelText('Nome')).toBeNull()
  })

  it('falha ao carregar a conta: aviso com "Tentar de novo", que consulta outra vez', async () => {
    const { api, user } = open(carteira, { get: networkError() })
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar a conta. Tente de novo.')

    api.on(wsRoute('GET', WID, `/accounts/${carteira.id}`), ok(carteira))
    await user.click(screen.getByRole('button', { name: 'Tentar de novo' }))

    expect(await ready()).toBeInTheDocument()
  })

  it('o leitor que abre o endereço vê o aviso de papel, e a conta nem é consultada', async () => {
    const { api } = open(carteira, { role: 'VIEWER' })

    expect(await screen.findByRole('heading', { name: 'Somente consulta' })).toBeInTheDocument()
    expect(api.callsTo(wsRoute('GET', WID, `/accounts/${carteira.id}`))).toHaveLength(0)
  })

  it('recarregar em /contas/:id abre direto o formulário daquela conta', async () => {
    open(carteira)

    expect(await ready()).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/contas/acc-carteira')
    expect(screen.getByRole('link', { name: 'Contas' })).toHaveAttribute('aria-current', 'page')
  })

  it('passa no axe (formulário e conta arquivada)', async () => {
    const form = open(carteira)
    await ready()
    await expectNoSeriousViolations(form.container)
    form.unmount()

    const archived = open(antiga)
    await screen.findByText('Conta arquivada')
    await expectNoSeriousViolations(archived.container)
  })
})
