import { act, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Account } from '../api/types'
import { formatBRL } from '../design-system/src'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, ok, problem, wsRoute, type Handler } from '../test/fakeApi'
import { WID, makeAccount, makeBalanceReport, makeMe } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const brl = (cents: number) => formatBRL(cents).replace(/ /g, ' ')

interface Options {
  role?: 'OWNER' | 'EDITOR' | 'VIEWER'
  archive?: Handler
  restore?: Handler
}

/** Uma API de mentira com memória: arquivar e restaurar mudam a conta e o que a lista e o total devolvem em seguida. */
function open(initial: Account[], { role = 'OWNER', archive, restore }: Options = {}) {
  const store = initial.map((account) => ({ ...account }))
  const find = (path: string) => store.find((account) => path.includes(`/accounts/${account.id}/`))!
  const api = fakeApi({
    'GET /api/v1/me': ok(makeMe(role)),
    [wsRoute('GET', WID, '/accounts')]: (call) => ok(call.path.includes('includeArchived=true') ? store : store.filter((a) => !a.archived)),
    [wsRoute('GET', WID, '/reports/balance')]: () => ok(makeBalanceReport(store)),
  })
  for (const account of store) {
    api.on(wsRoute('POST', WID, `/accounts/${account.id}/archive`), archive ?? ((call) => ((find(call.path).archived = true), ok(find(call.path)))))
    api.on(wsRoute('POST', WID, `/accounts/${account.id}/restore`), restore ?? ((call) => ((find(call.path).archived = false), ok(find(call.path)))))
  }
  return { api, store, ...renderApp(['/contas']) }
}

const carteira = () => makeAccount({ name: 'Carteira', type: 'CASH', balanceCents: 15000 })
const reserva = () => makeAccount({ name: 'Reserva', type: 'SAVINGS', balanceCents: 100000 })
const antiga = () => makeAccount({ name: 'Conta antiga', type: 'CHECKING', balanceCents: 999, archived: true })
const archivePath = (a: Account) => wsRoute('POST', WID, `/accounts/${a.id}/archive`)
const restorePath = (a: Account) => wsRoute('POST', WID, `/accounts/${a.id}/restore`)
const stat = () => screen.getByText('Saldo total').closest('.tx-stat') as HTMLElement
const listed = () => screen.findByRole('list', { name: 'Contas' })

describe('arquivar uma conta', () => {
  it('pede confirmação, explicando o efeito, com o foco em "Cancelar"', async () => {
    const [a] = [carteira()]
    const { user } = open([a, reserva()])
    await listed()

    await user.click(screen.getByRole('button', { name: 'Arquivar Carteira' }))

    const dialog = screen.getByRole('dialog', { name: 'Arquivar "Carteira"?' })
    expect(dialog).toHaveAccessibleDescription(/A conta sai da lista e do saldo total\. Os lançamentos dela continuam existindo/)
    expect(screen.getByRole('button', { name: 'Cancelar' })).toHaveFocus()
    expect(within(dialog).getByRole('button', { name: 'Arquivar' })).toHaveClass('tx-btn--primary')
  })

  it('cancelar (pelo botão ou por Esc) não chama a API e nada muda', async () => {
    const a = carteira()
    const { api, user } = open([a, reserva()])
    await listed()

    await user.click(screen.getByRole('button', { name: 'Arquivar Carteira' }))
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(screen.queryByRole('dialog')).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Arquivar Carteira' }))
    act(() => (screen.getByRole('dialog') as HTMLDialogElement).close())
    expect(screen.queryByRole('dialog')).toBeNull()

    expect(api.callsTo(archivePath(a))).toHaveLength(0)
    expect(screen.getByText('Carteira')).toBeInTheDocument()
    expect(screen.queryByText('Conta arquivada.')).toBeNull()
  })

  it('confirmar arquiva: a conta sai da lista, o total é atualizado e o resultado é anunciado', async () => {
    const a = carteira()
    const { api, user } = open([a, reserva()])
    await listed()
    expect(within(stat()).getByText(brl(115000))).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Arquivar Carteira' }))
    await user.click(screen.getByRole('button', { name: 'Arquivar' }))

    expect(await screen.findByText('Conta arquivada.')).toBeInTheDocument()
    await listed()
    expect(screen.queryByText('Carteira')).toBeNull()
    expect(within(stat()).getByText(brl(100000))).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(api.callsTo(archivePath(a))).toHaveLength(1)
  })

  it('com as arquivadas à vista, a conta arquivada continua na lista, marcada', async () => {
    const a = carteira()
    const { user } = open([a, reserva()])
    await listed()
    await user.click(screen.getByRole('checkbox', { name: 'Mostrar arquivadas' }))
    await screen.findByText('Carteira')

    await user.click(screen.getByRole('button', { name: 'Arquivar Carteira' }))
    await user.click(screen.getByRole('button', { name: 'Arquivar' }))

    expect(await screen.findByText('Conta arquivada.')).toBeInTheDocument()
    expect(await screen.findByText(/Dinheiro · arquivada/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Restaurar Carteira' })).toBeInTheDocument()
  })

  it('o envio duplicado (confirmar duas vezes) chama a API uma vez só', async () => {
    let release!: () => void
    const wait = new Promise<void>((resolve) => {
      release = resolve
    })
    const a = carteira()
    const { api, user } = open([a, reserva()], { archive: async () => (await wait, ok({ ...a, archived: true })) })
    await listed()

    await user.click(screen.getByRole('button', { name: 'Arquivar Carteira' }))
    await user.click(screen.getByRole('button', { name: 'Arquivar' }))
    expect(screen.getByRole('button', { name: 'Arquivar' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Cancelar' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Arquivar' }))

    await act(async () => release())
    expect(await screen.findByText('Conta arquivada.')).toBeInTheDocument()
    expect(api.callsTo(archivePath(a))).toHaveLength(1)
  })

  it.each([
    ['falha de rede', networkError(), 'Não foi possível falar com o servidor. Tente de novo.'],
    ['erro do servidor', problem(500, 'Erro'), 'Não foi possível falar com o servidor. Tente de novo.'],
    ['sem permissão', problem(403, 'Proibido'), 'Você não tem permissão para alterar isto neste workspace.'],
  ])('%s: a conta continua como estava e a pessoa é avisada', async (_name, failure, message) => {
    const a = carteira()
    const { user } = open([a, reserva()], { archive: failure })
    await listed()

    await user.click(screen.getByRole('button', { name: 'Arquivar Carteira' }))
    await user.click(screen.getByRole('button', { name: 'Arquivar' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.getByText('Carteira')).toBeInTheDocument()
    expect(within(stat()).getByText(brl(115000))).toBeInTheDocument()
    expect(screen.queryByText('Conta arquivada.')).toBeNull()
  })

  it('depois de uma falha, tentar de novo pode dar certo', async () => {
    const a = carteira()
    const { api, user } = open([a, reserva()], { archive: networkError() })
    await listed()
    await user.click(screen.getByRole('button', { name: 'Arquivar Carteira' }))
    await user.click(screen.getByRole('button', { name: 'Arquivar' }))
    await screen.findByRole('alert')

    api.on(archivePath(a), ok({ ...a, archived: true }))
    await user.click(screen.getByRole('button', { name: 'Arquivar Carteira' }))
    await user.click(screen.getByRole('button', { name: 'Arquivar' }))

    expect(await screen.findByText('Conta arquivada.')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('404 (a conta já não existe): avisa e a lista é recarregada', async () => {
    const a = carteira()
    const { api, user } = open([a, reserva()], { archive: problem(404, 'Não encontrada') })
    await listed()

    await user.click(screen.getByRole('button', { name: 'Arquivar Carteira' }))
    await user.click(screen.getByRole('button', { name: 'Arquivar' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Não encontrada. A lista foi atualizada.')
    await listed()
    expect(api.callsTo(wsRoute('GET', WID, '/accounts')).length).toBeGreaterThanOrEqual(2)
  })
})

describe('restaurar uma conta', () => {
  it('é direto (sem confirmação): a conta volta à lista e ao total', async () => {
    const old = antiga()
    const { api, user } = open([carteira(), old])
    await listed()
    await user.click(screen.getByRole('checkbox', { name: 'Mostrar arquivadas' }))
    await screen.findByText('Conta antiga')
    expect(within(stat()).getByText(brl(15000))).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Restaurar Conta antiga' }))

    expect(await screen.findByText('Conta restaurada.')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).toBeNull()
    await screen.findByText('Conta antiga')
    expect(screen.queryByText(/arquivada\b/)).toBeNull()
    expect(within(stat()).getByText(brl(15999))).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Arquivar Conta antiga' })).toBeInTheDocument()
    expect(api.callsTo(restorePath(old))).toHaveLength(1)
  })

  it('409: o nome já tomado por uma conta ativa é explicado e a conta continua arquivada', async () => {
    const { user } = open([carteira(), antiga()], { restore: problem(409, 'Nome em uso') })
    await listed()
    await user.click(screen.getByRole('checkbox', { name: 'Mostrar arquivadas' }))
    await screen.findByText('Conta antiga')

    await user.click(screen.getByRole('button', { name: 'Restaurar Conta antiga' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Já existe uma conta ativa com este nome. Renomeie a ativa antes de restaurar esta.')
    expect(screen.getByText(/Conta corrente · arquivada/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Restaurar Conta antiga' })).toBeEnabled()
  })

  it('falha de rede: avisa e a conta continua arquivada', async () => {
    const { user } = open([carteira(), antiga()], { restore: networkError() })
    await listed()
    await user.click(screen.getByRole('checkbox', { name: 'Mostrar arquivadas' }))
    await screen.findByText('Conta antiga')

    await user.click(screen.getByRole('button', { name: 'Restaurar Conta antiga' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível falar com o servidor.')
    expect(screen.getByText(/Conta corrente · arquivada/)).toBeInTheDocument()
  })
})

describe('quem só consulta e a acessibilidade', () => {
  it('o leitor não vê "Arquivar" nem "Restaurar"', async () => {
    const { user } = open([carteira(), antiga()], { role: 'VIEWER' })
    await listed()
    await user.click(screen.getByRole('checkbox', { name: 'Mostrar arquivadas' }))
    await screen.findByText('Conta antiga')

    expect(screen.queryByRole('button', { name: /^(Arquivar|Restaurar)/ })).toBeNull()
  })

  it('a lista com as ações e a confirmação aberta passam no axe', async () => {
    const { container, user } = open([carteira(), reserva()])
    await listed()
    await expectNoSeriousViolations(container)

    await user.click(screen.getByRole('button', { name: 'Arquivar Carteira' }))
    await expectNoSeriousViolations(container)
  })
})
