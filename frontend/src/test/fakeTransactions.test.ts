// A API de mentira de lançamentos precisa seguir o contrato, senão os testes de tela provam um comportamento que a API real não tem.
import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/errors'
import { clearTransaction, createTransaction, deleteTransaction, getTransaction, listTransactions, updateTransaction } from '../api/transactions'
import type { CreateTransaction, Transaction, UpdateTransaction } from '../api/types'
import { fakeApi } from './fakeApi'
import { installTransactions } from './fakeTransactions'
import { makeCategoryTree, makeCategory, makeTransaction } from './fixtures'

const WID = 'w-teste'
const setup = (initial: Transaction[] = [], categories = [] as ReturnType<typeof makeCategoryTree>[]) => {
  const api = fakeApi()
  const memory = installTransactions(api, initial, { workspaceId: WID, categories })
  return { api, ...memory }
}
const expense = (extra: Partial<CreateTransaction> = {}): CreateTransaction => ({ type: 'EXPENSE', accountId: 'A', amountCents: 3590, occurredOn: '2026-11-11', description: 'Almoço', categoryId: 'c', ...extra })
const error = async (promise: Promise<unknown>) => (await promise.then(() => null, (e: unknown) => e)) as ApiError

describe('a lista', () => {
  const many = Array.from({ length: 45 }, (_, i) => makeTransaction({ id: `m${i}`, occurredOn: `2026-10-${String((i % 28) + 1).padStart(2, '0')}`, description: `Item ${i}`, createdAt: `2026-01-01T00:00:${String(i).padStart(2, '0')}.000Z` }))

  it('pagina de 20 em 20, do mais recente ao mais antigo, com os totais', async () => {
    setup(many)

    const first = await listTransactions(WID, 1)
    const last = await listTransactions(WID, 3)

    expect(first).toMatchObject({ page: 1, size: 20, totalItems: 45, totalPages: 3 })
    expect(first.items).toHaveLength(20)
    expect(last.items).toHaveLength(5)
    const dates = (await listTransactions(WID, 1)).items.map((t) => t.occurredOn)
    expect(dates).toEqual([...dates].sort().reverse())
  })

  it('uma página além do fim vem vazia, com os totais corretos', async () => {
    setup(many)

    const page = await listTransactions(WID, 9)

    expect(page).toMatchObject({ page: 9, totalItems: 45, totalPages: 3, items: [] })
  })

  it('sem nenhum lançamento: página 1, zero itens, zero páginas', async () => {
    setup()

    expect(await listTransactions(WID, 1)).toEqual({ items: [], page: 1, size: 20, totalItems: 0, totalPages: 0 })
  })

  it('período inclusivo nas duas pontas', async () => {
    setup([makeTransaction({ occurredOn: '2026-11-01' }), makeTransaction({ occurredOn: '2026-11-15' }), makeTransaction({ occurredOn: '2026-11-30' }), makeTransaction({ occurredOn: '2026-12-01' })])

    const page = await listTransactions(WID, 1, { from: '2026-11-01', to: '2026-11-30' })

    expect(page.items.map((t) => t.occurredOn)).toEqual(['2026-11-30', '2026-11-15', '2026-11-01'])
  })

  it('data final antes da inicial é 400 no campo "to"', async () => {
    setup()

    const e = await error(listTransactions(WID, 1, { from: '2026-11-10', to: '2026-11-01' }))

    expect(e).toBeInstanceOf(ApiError)
    expect(e.status).toBe(400)
    expect(e.errors.map((x) => x.field)).toEqual(['to'])
  })

  it('conta, tipo e estado', async () => {
    setup([
      makeTransaction({ accountId: 'A', type: 'EXPENSE', status: 'CLEARED' }),
      makeTransaction({ accountId: 'B', type: 'INCOME', amountCents: 500, status: 'PENDING' }),
      makeTransaction({ accountId: 'B', type: 'EXPENSE', status: 'PENDING' }),
    ])

    expect((await listTransactions(WID, 1, { accountId: 'B' })).totalItems).toBe(2)
    expect((await listTransactions(WID, 1, { type: 'INCOME' })).totalItems).toBe(1)
    expect((await listTransactions(WID, 1, { status: 'PENDING' })).totalItems).toBe(2)
    expect((await listTransactions(WID, 1, { accountId: 'B', status: 'PENDING', type: 'EXPENSE' })).totalItems).toBe(1)
  })

  it('a categoria principal inclui as subcategorias; uma subcategoria só a si mesma', async () => {
    const sub = makeCategory({ id: 'sub', name: 'Mercado' })
    const tree = makeCategoryTree({ id: 'principal', name: 'Alimentação' }, [sub])
    // (makeCategoryTree gera o próprio id; usa-se o que ele gerou)
    setup([makeTransaction({ categoryId: tree.id }), makeTransaction({ categoryId: tree.subcategories[0].id }), makeTransaction({ categoryId: 'outra' })], [tree])

    expect((await listTransactions(WID, 1, { categoryId: tree.id })).totalItems).toBe(2)
    expect((await listTransactions(WID, 1, { categoryId: tree.subcategories[0].id })).totalItems).toBe(1)
  })

  it('a busca é por trecho, sem diferenciar maiúsculas, e % e _ valem literalmente', async () => {
    setup([
      makeTransaction({ description: 'Mercado do Zé' }),
      makeTransaction({ description: 'Desconto de 50% na loja' }),
      makeTransaction({ description: 'conta_de_luz' }),
      makeTransaction({ description: 'contaXdeXluz' }),
    ])

    expect((await listTransactions(WID, 1, { q: 'MERCADO' })).totalItems).toBe(1)
    expect((await listTransactions(WID, 1, { q: '50%' })).totalItems).toBe(1)
    expect((await listTransactions(WID, 1, { q: '%' })).totalItems).toBe(1)
    expect((await listTransactions(WID, 1, { q: 'conta_de' })).totalItems).toBe(1)
    expect((await listTransactions(WID, 1, { q: '_' })).totalItems).toBe(1)
  })

  it('busca de 101 caracteres é 400 no campo "q"', async () => {
    setup()

    const e = await error(listTransactions(WID, 1, { q: 'a'.repeat(101) }))

    expect(e.errors.map((x) => x.field)).toEqual(['q'])
  })
})

describe('criar', () => {
  it('despesa sai negativa, receita positiva; o valor chega positivo', async () => {
    const { store } = setup()

    const out = await createTransaction(WID, expense())
    const income = await createTransaction(WID, expense({ type: 'INCOME', description: 'Salário' }))

    expect(out).toMatchObject({ type: 'EXPENSE', amountCents: -3590, status: 'CLEARED', categoryId: 'c' })
    expect(income).toMatchObject({ type: 'INCOME', amountCents: 3590 })
    expect(store).toHaveLength(2)
  })

  it('pendente quando pedido; descrição e observações sem os espaços das pontas', async () => {
    setup()

    const created = await createTransaction(WID, expense({ status: 'PENDING', description: '  Almoço  ', notes: '  obs  ' }))

    expect(created).toMatchObject({ status: 'PENDING', description: 'Almoço', notes: 'obs' })
  })

  it('a transferência cria as duas pontas ligadas: saída na origem, entrada no destino, sem categoria', async () => {
    const { store } = setup()

    const created = await createTransaction(WID, { type: 'TRANSFER', accountId: 'A', destinationAccountId: 'B', amountCents: 10000, occurredOn: '2026-11-11', description: 'Reserva' })

    expect(store).toHaveLength(2)
    const [out, into] = store
    expect(created.id).toBe(out.id)
    expect(out).toMatchObject({ accountId: 'A', counterpartAccountId: 'B', amountCents: -10000, categoryId: null })
    expect(into).toMatchObject({ accountId: 'B', counterpartAccountId: 'A', amountCents: 10000, categoryId: null })
    expect(out.transferGroupId).toBe(into.transferGroupId)
    expect(out.transferGroupId).not.toBeNull()
  })

  it.each([
    ['valor zero', { amountCents: 0 }, 'amountCents'],
    ['valor acima do limite', { amountCents: 1_000_000_000_001 }, 'amountCents'],
    ['valor não inteiro', { amountCents: 10.5 }, 'amountCents'],
    ['descrição vazia', { description: '   ' }, 'description'],
    ['descrição de 201 caracteres', { description: 'a'.repeat(201) }, 'description'],
    ['observações de 1001 caracteres', { notes: 'a'.repeat(1001) }, 'notes'],
    ['data inexistente', { occurredOn: '2026-02-30' }, 'occurredOn'],
    ['ano fora do intervalo', { occurredOn: '2101-01-01' }, 'occurredOn'],
    ['despesa sem categoria', { categoryId: undefined }, 'categoryId'],
    ['despesa com destino', { destinationAccountId: 'B' }, 'destinationAccountId'],
    ['estado inválido', { status: 'cleared' as never }, 'status'],
  ])('%s é 400 no campo certo', async (_name, change, field) => {
    setup()

    const e = await error(createTransaction(WID, expense(change as Partial<CreateTransaction>)))

    expect(e.status).toBe(400)
    expect(e.errors.map((x) => x.field)).toContain(field)
  })

  it('a transferência recusa origem igual ao destino, categoria e destino ausente', async () => {
    setup()
    const transfer: CreateTransaction = { type: 'TRANSFER', accountId: 'A', destinationAccountId: 'B', amountCents: 100, occurredOn: '2026-11-11', description: 'x' }

    expect((await error(createTransaction(WID, { ...transfer, destinationAccountId: 'A' }))).errors.map((x) => x.field)).toEqual(['destinationAccountId'])
    expect((await error(createTransaction(WID, { ...transfer, categoryId: 'c' }))).errors.map((x) => x.field)).toEqual(['categoryId'])
    expect((await error(createTransaction(WID, { ...transfer, destinationAccountId: undefined }))).errors.map((x) => x.field)).toEqual(['destinationAccountId'])
  })
})

describe('ler, editar, efetivar e excluir', () => {
  const update = (extra: Partial<UpdateTransaction> = {}): UpdateTransaction => ({ accountId: 'A', amountCents: 5000, occurredOn: '2026-11-12', description: 'Novo', categoryId: 'c', status: 'CLEARED', ...extra })

  it('ler um lançamento; um que não existe é 404', async () => {
    const tx = makeTransaction({ id: 'x1' })
    setup([tx])

    expect(await getTransaction(WID, 'x1')).toMatchObject({ id: 'x1' })
    expect((await error(getTransaction(WID, 'nao-existe'))).status).toBe(404)
  })

  it('editar mantém o sinal pelo tipo e a edição é completa (observação ausente limpa)', async () => {
    const tx = makeTransaction({ id: 'x1', type: 'EXPENSE', amountCents: -100, notes: 'antiga' })
    setup([tx])

    const saved = await updateTransaction(WID, 'x1', update())

    expect(saved).toMatchObject({ amountCents: -5000, description: 'Novo', occurredOn: '2026-11-12', notes: null })
  })

  it('mudar o tipo é recusado; um tipo igual é aceito', async () => {
    setup([makeTransaction({ id: 'x1', type: 'EXPENSE' })])

    expect((await error(updateTransaction(WID, 'x1', { ...update(), type: 'INCOME' } as UpdateTransaction))).errors.map((x) => x.field)).toEqual(['type'])
    await expect(updateTransaction(WID, 'x1', { ...update(), type: 'EXPENSE' } as UpdateTransaction)).resolves.toBeTruthy()
  })

  it('editar uma transferência por QUALQUER ponta muda as duas, com origem e destino da requisição', async () => {
    const { store } = setup()
    const created = await createTransaction(WID, { type: 'TRANSFER', accountId: 'A', destinationAccountId: 'B', amountCents: 10000, occurredOn: '2026-11-11', description: 'Reserva' })
    const intoId = store.find((end) => end.amountCents > 0)!.id
    expect(created.id).not.toBe(intoId)

    await updateTransaction(WID, intoId, { accountId: 'A', destinationAccountId: 'C', amountCents: 2500, occurredOn: '2026-11-13', description: 'Novo destino', status: 'CLEARED' })

    const out = store.find((end) => end.amountCents < 0)!
    const into = store.find((end) => end.amountCents > 0)!
    expect(out).toMatchObject({ accountId: 'A', counterpartAccountId: 'C', amountCents: -2500, description: 'Novo destino' })
    expect(into).toMatchObject({ accountId: 'C', counterpartAccountId: 'A', amountCents: 2500, description: 'Novo destino' })
  })

  it('efetivar muda o estado (a transferência, nas duas pontas) e efetivar de novo não dá erro', async () => {
    const { store } = setup()
    await createTransaction(WID, { type: 'TRANSFER', accountId: 'A', destinationAccountId: 'B', amountCents: 100, occurredOn: '2026-11-11', description: 'x', status: 'PENDING' })
    expect(store.every((end) => end.status === 'PENDING')).toBe(true)

    await clearTransaction(WID, store[0].id)
    await clearTransaction(WID, store[0].id)

    expect(store.every((end) => end.status === 'CLEARED')).toBe(true)
  })

  it('editar de volta para pendente', async () => {
    setup([makeTransaction({ id: 'x1', status: 'CLEARED' })])

    expect((await updateTransaction(WID, 'x1', update({ status: 'PENDING' }))).status).toBe('PENDING')
  })

  it('excluir tira o lançamento (a transferência, as duas pontas); excluir de novo é 404', async () => {
    const { store } = setup([makeTransaction({ id: 'solto' })])
    await createTransaction(WID, { type: 'TRANSFER', accountId: 'A', destinationAccountId: 'B', amountCents: 100, occurredOn: '2026-11-11', description: 'x' })
    expect(store).toHaveLength(3)

    await deleteTransaction(WID, store[1].id)
    expect(store.map((t) => t.id)).toEqual(['solto'])
    expect((await error(deleteTransaction(WID, 'solto').then(() => deleteTransaction(WID, 'solto')))).status).toBe(404)
  })

  it('editar, efetivar e excluir o que não existe é 404', async () => {
    setup()

    expect((await error(updateTransaction(WID, 'nao', update()))).status).toBe(404)
    expect((await error(clearTransaction(WID, 'nao'))).status).toBe(404)
    expect((await error(deleteTransaction(WID, 'nao'))).status).toBe(404)
  })
})
