import { act, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Account, CategoryTree, Transaction } from '../api/types'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, ok, problem, wsRoute, type Handler } from '../test/fakeApi'
import { installTransactions } from '../test/fakeTransactions'
import { WID, makeAccount, makeCategory, makeCategoryTree, makeMe, makeTransaction } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const carteira = makeAccount({ id: 'a-carteira', name: 'Carteira' })
const reserva = makeAccount({ id: 'a-reserva', name: 'Reserva' })
const accounts: Account[] = [carteira, reserva]
const mercado = makeCategory({ id: 'c-mercado', name: 'Mercado' })
const alimentacao: CategoryTree = makeCategoryTree({ id: 'c-alim', name: 'Alimentação', kind: 'EXPENSE' }, [mercado])
const categories = [alimentacao]

const bill = (extra: Partial<Transaction> = {}) => makeTransaction({ id: 'tx-bill', accountId: 'a-carteira', categoryId: 'c-mercado', amountCents: -9900, description: 'Conta de luz', status: 'PENDING', occurredOn: '2026-11-20', ...extra })
const pendingTransfer = () => [
  makeTransaction({ id: 'tx-out', type: 'TRANSFER', accountId: 'a-carteira', counterpartAccountId: 'a-reserva', amountCents: -5000, description: 'Reserva futura', status: 'PENDING', transferGroupId: 'g', categoryId: null, occurredOn: '2026-11-21' }),
  makeTransaction({ id: 'tx-in', type: 'TRANSFER', accountId: 'a-reserva', counterpartAccountId: 'a-carteira', amountCents: 5000, description: 'Reserva futura', status: 'PENDING', transferGroupId: 'g', categoryId: null, occurredOn: '2026-11-21' }),
]

function open(initial: Transaction[], id: string, { role = 'OWNER' as 'OWNER' | 'EDITOR' | 'VIEWER', clear }: { role?: 'OWNER' | 'EDITOR' | 'VIEWER'; clear?: Handler } = {}) {
  const api = fakeApi({
    'GET /api/v1/me': ok(makeMe(role)),
    [wsRoute('GET', WID, '/accounts')]: ok(accounts),
    [wsRoute('GET', WID, '/categories')]: ok(categories),
  })
  const memory = installTransactions(api, initial, { workspaceId: WID, categories })
  const CLEAR = wsRoute('POST', WID, `/transactions/${id}/clear`)
  if (clear) api.onMatch('POST', new RegExp(`/transactions/${id}/clear$`), (call) => (typeof clear === 'function' ? clear(call) : clear) as never)
  return { api, memory, CLEAR, ...renderApp([`/lancamentos/${id}`]) }
}
const editReady = (title = 'Editar lançamento') => screen.findByRole('heading', { name: title })

describe('efetivar um lançamento pendente', () => {
  it('só um pendente tem "Efetivar"; um efetivado, não', async () => {
    const pending = open([bill()], 'tx-bill')
    await editReady()
    expect(screen.getByRole('button', { name: 'Efetivar' })).toBeInTheDocument()
    pending.unmount()

    open([bill({ status: 'CLEARED' })], 'tx-bill')
    await editReady()
    expect(screen.queryByRole('button', { name: 'Efetivar' })).toBeNull()
  })

  it('efetiva, volta à lista com o aviso e a marca de pendente some', async () => {
    const { api, memory, CLEAR, user } = open([bill()], 'tx-bill')
    await editReady()

    await user.click(screen.getByRole('button', { name: 'Efetivar' }))

    expect(await screen.findByText('Lançamento efetivado.')).toBeInTheDocument()
    const row = await screen.findByRole('button', { name: /Conta de luz/ })
    expect(row).not.toHaveTextContent('Pendente')
    expect(api.callsTo(CLEAR)).toHaveLength(1)
    expect(memory.store[0].status).toBe('CLEARED')
  })

  it('numa transferência efetiva as duas pontas, por qualquer ponta aberta', async () => {
    const { memory, user } = open(pendingTransfer(), 'tx-in')
    await editReady('Editar transferência')
    expect(screen.getByText(/as duas pontas da transferência/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Efetivar' }))

    await screen.findByText('Lançamento efetivado.')
    expect(memory.store.every((end) => end.status === 'CLEARED')).toBe(true)
  })

  it('o envio duplicado (dois cliques) chama a API uma vez só e mostra que está efetivando', async () => {
    let release!: () => void
    const wait = new Promise<void>((resolve) => {
      release = resolve
    })
    const { api, CLEAR, user } = open([bill()], 'tx-bill', { clear: async () => (await wait, ok(bill({ status: 'CLEARED' }))) })
    await editReady()

    await user.click(screen.getByRole('button', { name: 'Efetivar' }))
    await user.click(screen.getByRole('button', { name: 'Efetivando…' }))

    expect(screen.getByRole('button', { name: 'Efetivando…' })).toBeDisabled()
    await act(async () => release())
    expect(await screen.findByText('Lançamento efetivado.')).toBeInTheDocument()
    expect(api.callsTo(CLEAR)).toHaveLength(1)
  })

  it.each([
    ['falha de rede', networkError(), 'Não foi possível falar com o servidor. Tente de novo.'],
    ['erro do servidor', problem(500, 'Erro'), 'Não foi possível falar com o servidor. Tente de novo.'],
    ['sem permissão', problem(403, 'Proibido'), 'Você não tem permissão para alterar isto neste workspace.'],
  ])('%s: o lançamento continua pendente e a pessoa é avisada', async (_name, failure, message) => {
    const { memory, user } = open([bill()], 'tx-bill', { clear: failure })
    await editReady()

    await user.click(screen.getByRole('button', { name: 'Efetivar' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    expect(screen.getByRole('button', { name: 'Efetivar' })).toBeEnabled()
    expect(memory.store[0].status).toBe('PENDING')
    expect(screen.queryByText('Lançamento efetivado.')).toBeNull()
  })

  it('depois de uma falha, tentar de novo pode dar certo', async () => {
    const { api, user } = open([bill()], 'tx-bill', { clear: networkError() })
    await editReady()
    await user.click(screen.getByRole('button', { name: 'Efetivar' }))
    await screen.findByRole('alert')

    api.onMatch('POST', /\/transactions\/tx-bill\/clear$/, () => ok(bill({ status: 'CLEARED' })))
    await user.click(screen.getByRole('button', { name: 'Efetivar' }))

    expect(await screen.findByText('Lançamento efetivado.')).toBeInTheDocument()
  })

  it('404 (excluído em outro aparelho): volta à lista atualizada, com o aviso', async () => {
    const { user } = open([bill()], 'tx-bill', { clear: problem(404, 'Não encontrado') })
    await editReady()

    await user.click(screen.getByRole('button', { name: 'Efetivar' }))

    expect(await screen.findByText('Não encontrada. A lista foi atualizada.')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Lançamentos' })).toBeInTheDocument()
  })

  it('o leitor não vê "Efetivar" (só consulta)', async () => {
    open([bill()], 'tx-bill', { role: 'VIEWER' })

    expect(await screen.findByRole('heading', { name: 'Lançamento' })).toBeInTheDocument()
    expect(screen.getByText('Pendente')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Efetivar' })).toBeNull()
  })

  it('a página com a ação passa no axe', async () => {
    const { container } = open([bill()], 'tx-bill')
    await editReady()

    await expectNoSeriousViolations(container)
  })
})
