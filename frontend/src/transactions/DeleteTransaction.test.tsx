import { act, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Account, CategoryTree, Transaction } from '../api/types'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, ok, problem, wsRoute, type Handler } from '../test/fakeApi'
import { installTransactions } from '../test/fakeTransactions'
import { WID, makeAccount, makeCategory, makeCategoryTree, makeMe, makeTransaction } from '../test/fixtures'
import { renderApp } from '../test/renderApp'
import { deleteText } from './rows'

const carteira = makeAccount({ id: 'a-carteira', name: 'Carteira' })
const reserva = makeAccount({ id: 'a-reserva', name: 'Reserva' })
const accounts: Account[] = [carteira, reserva]
const mercado = makeCategory({ id: 'c-mercado', name: 'Mercado' })
const alimentacao: CategoryTree = makeCategoryTree({ id: 'c-alim', name: 'Alimentação', kind: 'EXPENSE' }, [mercado])
const categories = [alimentacao]

const lunch = (extra: Partial<Transaction> = {}) => makeTransaction({ id: 'tx-lunch', accountId: 'a-carteira', categoryId: 'c-mercado', amountCents: -3590, description: 'Almoço', occurredOn: '2026-11-03', ...extra })
const other = () => makeTransaction({ id: 'tx-other', accountId: 'a-carteira', categoryId: 'c-mercado', amountCents: -100, description: 'Outro', occurredOn: '2026-11-02' })
const transferEnds = () => [
  makeTransaction({ id: 'tx-out', type: 'TRANSFER', accountId: 'a-carteira', counterpartAccountId: 'a-reserva', amountCents: -10000, description: 'Reserva', transferGroupId: 'g', categoryId: null, occurredOn: '2026-11-06' }),
  makeTransaction({ id: 'tx-in', type: 'TRANSFER', accountId: 'a-reserva', counterpartAccountId: 'a-carteira', amountCents: 10000, description: 'Reserva', transferGroupId: 'g', categoryId: null, occurredOn: '2026-11-06' }),
]

function open(initial: Transaction[], id: string, { role = 'OWNER' as 'OWNER' | 'EDITOR' | 'VIEWER', remove }: { role?: 'OWNER' | 'EDITOR' | 'VIEWER'; remove?: Handler } = {}) {
  const api = fakeApi({
    'GET /api/v1/me': ok(makeMe(role)),
    [wsRoute('GET', WID, '/accounts')]: ok(accounts),
    [wsRoute('GET', WID, '/categories')]: ok(categories),
  })
  const memory = installTransactions(api, initial, { workspaceId: WID, categories })
  const DELETE = wsRoute('DELETE', WID, `/transactions/${id}`)
  if (remove) api.onMatch('DELETE', new RegExp(`/transactions/${id}$`), (call) => (typeof remove === 'function' ? remove(call) : remove) as never)
  return { api, memory, DELETE, ...renderApp([`/lancamentos/${id}`]) }
}
const editReady = (title = 'Editar lançamento') => screen.findByRole('heading', { name: title })
const openDialog = async (user: ReturnType<typeof open>['user']) => {
  await user.click(screen.getByRole('button', { name: 'Excluir' }))
  return screen.getByRole('dialog')
}

describe('o texto do efeito', () => {
  const base = { type: 'EXPENSE' as const, installmentNumber: null, recurrenceId: null }

  it('conforme o tipo do lançamento', () => {
    expect(deleteText(base)).toBe('O lançamento sai da lista e do saldo da conta.')
    expect(deleteText({ ...base, type: 'TRANSFER' })).toBe('A transferência sai da lista e do saldo das duas contas.')
    expect(deleteText({ ...base, installmentNumber: 2 })).toBe('O lançamento sai da lista e do saldo da conta. Só esta parcela é excluída.')
    expect(deleteText({ ...base, recurrenceId: 'r1' })).toBe('O lançamento sai da lista e do saldo da conta. Só esta ocorrência é excluída.')
  })
})

describe('excluir um lançamento', () => {
  it('pede confirmação de perigo, explicando o efeito, com o foco em "Cancelar"', async () => {
    const { user } = open([lunch()], 'tx-lunch')
    await editReady()

    const dialog = await openDialog(user)

    expect(dialog).toHaveAccessibleName('Excluir este lançamento?')
    expect(dialog).toHaveAccessibleDescription('O lançamento sai da lista e do saldo da conta.')
    expect(screen.getByRole('button', { name: 'Cancelar' })).toHaveFocus()
    expect(within(dialog).getByRole('button', { name: 'Excluir' })).toHaveClass('tx-btn--danger')
  })

  it('a confirmação de uma transferência, de uma parcela e de uma recorrência dizem o seu efeito', async () => {
    const transfer = open(transferEnds(), 'tx-in')
    await editReady('Editar transferência')
    expect(await openDialog(transfer.user)).toHaveAccessibleDescription('A transferência sai da lista e do saldo das duas contas.')
    expect(screen.getByRole('dialog')).toHaveAccessibleName('Excluir esta transferência?')
    transfer.unmount()

    const parcela = open([lunch({ installmentGroupId: 'ig', installmentNumber: 2, installmentCount: 5 })], 'tx-lunch')
    await editReady()
    expect(await openDialog(parcela.user)).toHaveAccessibleDescription(/Só esta parcela é excluída\./)
    parcela.unmount()

    const mensal = open([lunch({ recurrenceId: 'r1', occurrenceNumber: 3 })], 'tx-lunch')
    await editReady()
    expect(await openDialog(mensal.user)).toHaveAccessibleDescription(/Só esta ocorrência é excluída\./)
  })

  it('cancelar (pelo botão e por Esc) não chama a API e nada muda', async () => {
    const { api, memory, DELETE, user } = open([lunch()], 'tx-lunch')
    await editReady()

    await openDialog(user)
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(screen.queryByRole('dialog')).toBeNull()

    await openDialog(user)
    act(() => (screen.getByRole('dialog') as HTMLDialogElement).close())
    expect(screen.queryByRole('dialog')).toBeNull()

    expect(api.callsTo(DELETE)).toHaveLength(0)
    expect(memory.store).toHaveLength(1)
    expect(screen.getByRole('heading', { name: 'Editar lançamento' })).toBeInTheDocument()
  })

  it('confirmar exclui, volta à lista com o aviso e o lançamento não aparece mais', async () => {
    const { api, memory, DELETE, user } = open([lunch(), other()], 'tx-lunch')
    await editReady()

    await openDialog(user)
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Excluir' }))

    expect(await screen.findByText('Lançamento excluído.')).toBeInTheDocument()
    expect(await screen.findByText('1 lançamento')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Almoço/ })).toBeNull()
    expect(screen.getByRole('button', { name: /Outro/ })).toBeInTheDocument()
    expect(api.callsTo(DELETE)).toHaveLength(1)
    expect(memory.store.map((t) => t.id)).toEqual(['tx-other'])
  })

  it('excluir uma transferência (por qualquer ponta) tira as duas pontas', async () => {
    const { memory, user } = open(transferEnds(), 'tx-in')
    await editReady('Editar transferência')

    await openDialog(user)
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Excluir' }))

    await screen.findByText('Lançamento excluído.')
    expect(memory.store).toHaveLength(0)
    expect(await screen.findByText('Nenhum lançamento ainda')).toBeInTheDocument()
  })

  it('o envio duplicado (confirmar duas vezes) chama a API uma vez só', async () => {
    let release!: () => void
    const wait = new Promise<void>((resolve) => {
      release = resolve
    })
    const { api, DELETE, user } = open([lunch()], 'tx-lunch', { remove: async () => (await wait, { status: 204 }) })
    await editReady()

    await openDialog(user)
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Excluir' }))
    expect(screen.getByRole('button', { name: 'Cancelar' })).toBeDisabled()
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Excluir' }))

    await act(async () => release())
    expect(await screen.findByText('Lançamento excluído.')).toBeInTheDocument()
    expect(api.callsTo(DELETE)).toHaveLength(1)
  })

  it.each([
    ['falha de rede', networkError(), 'Não foi possível falar com o servidor. Tente de novo.'],
    ['erro do servidor', problem(500, 'Erro'), 'Não foi possível falar com o servidor. Tente de novo.'],
    ['sem permissão', problem(403, 'Proibido'), 'Você não tem permissão para alterar isto neste workspace.'],
  ])('%s: o lançamento continua e a pessoa é avisada, com o diálogo fechado', async (_name, failure, message) => {
    const { memory, user } = open([lunch()], 'tx-lunch', { remove: failure })
    await editReady()

    await openDialog(user)
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Excluir' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(memory.store).toHaveLength(1)
    expect(screen.getByRole('heading', { name: 'Editar lançamento' })).toBeInTheDocument()
    expect(screen.queryByText('Lançamento excluído.')).toBeNull()
  })

  it('depois de uma falha, tentar de novo pode dar certo', async () => {
    const { api, memory, user } = open([lunch()], 'tx-lunch', { remove: networkError() })
    await editReady()
    await openDialog(user)
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Excluir' }))
    await screen.findByRole('alert')

    api.onMatch('DELETE', /\/transactions\/tx-lunch$/, () => ({ status: 204 }))
    await openDialog(user)
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Excluir' }))

    expect(await screen.findByText('Lançamento excluído.')).toBeInTheDocument()
    expect(memory.store).toBeDefined()
  })

  it('404 (já excluído em outro aparelho): volta à lista atualizada, com o aviso', async () => {
    const { user } = open([lunch()], 'tx-lunch', { remove: problem(404, 'Não encontrado') })
    await editReady()

    await openDialog(user)
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Excluir' }))

    expect(await screen.findByText('Não encontrada. A lista foi atualizada.')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Lançamentos' })).toBeInTheDocument()
  })

  it('o leitor não vê "Excluir"', async () => {
    open([lunch()], 'tx-lunch', { role: 'VIEWER' })

    expect(await screen.findByRole('heading', { name: 'Lançamento' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Excluir' })).toBeNull()
  })

  it('a página com a ação e a confirmação aberta passam no axe', async () => {
    const { container, user } = open([lunch()], 'tx-lunch')
    await editReady()
    await expectNoSeriousViolations(container)

    await openDialog(user)
    await expectNoSeriousViolations(container)
  })
})
