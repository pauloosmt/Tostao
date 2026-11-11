import { describe, expect, it } from 'vitest'
import { fakeApi, noContent, ok, wsRoute } from '../test/fakeApi'
import { PAGE_SIZE, clearTransaction, createTransaction, deleteTransaction, getTransaction, listQuery, listTransactions, updateTransaction } from './transactions'
import type { Transaction, TransactionPage } from './types'

const WID = '11111111-1111-1111-1111-111111111111'
const ID = '22222222-2222-2222-2222-222222222222'
const page: TransactionPage = { items: [], page: 1, size: 20, totalItems: 0, totalPages: 0 }
const tx = { id: ID } as Transaction

describe('a consulta da lista', () => {
  it('sem filtros leva só a página e o tamanho', () => {
    expect(listQuery(1, {})).toBe('page=1&size=20')
    expect(PAGE_SIZE).toBe(20)
  })

  it('só os filtros preenchidos entram, na ordem do contrato', () => {
    const query = listQuery(3, {
      q: 'mercado',
      status: 'PENDING',
      type: 'EXPENSE',
      categoryId: 'cat',
      accountId: 'acc',
      to: '2026-11-30',
      from: '2026-11-01',
    })

    expect(query).toBe('page=3&size=20&from=2026-11-01&to=2026-11-30&accountId=acc&categoryId=cat&type=EXPENSE&status=PENDING&q=mercado')
  })

  it('texto vazio e indefinido valem como "sem filtro"', () => {
    expect(listQuery(1, { q: '', from: undefined, accountId: '' })).toBe('page=1&size=20')
  })

  it('a busca é codificada: espaços, % e _ chegam inteiros à API', () => {
    expect(listQuery(1, { q: '50% de_desconto & mais' })).toBe('page=1&size=20&q=50%25+de_desconto+%26+mais')
    expect(new URLSearchParams(listQuery(1, { q: '50% de_desconto & mais' })).get('q')).toBe('50% de_desconto & mais')
  })
})

describe('as chamadas', () => {
  it('cada uma usa o método e o caminho do contrato, no workspace dado, com o corpo exato', async () => {
    const api = fakeApi({
      [wsRoute('GET', WID, '/transactions')]: ok(page),
      [wsRoute('GET', WID, `/transactions/${ID}`)]: ok(tx),
      [wsRoute('POST', WID, '/transactions')]: ok(tx, 201),
      [wsRoute('PUT', WID, `/transactions/${ID}`)]: ok(tx),
      [wsRoute('POST', WID, `/transactions/${ID}/clear`)]: ok(tx),
      [wsRoute('DELETE', WID, `/transactions/${ID}`)]: noContent(),
    })

    await listTransactions(WID, 2, { type: 'INCOME' })
    await getTransaction(WID, ID)
    await createTransaction(WID, { type: 'EXPENSE', accountId: 'a', amountCents: 3590, occurredOn: '2026-11-11', description: 'Almoço', categoryId: 'c', status: 'PENDING' })
    await updateTransaction(WID, ID, { accountId: 'a', amountCents: 100, occurredOn: '2026-11-11', description: 'x', status: 'CLEARED' })
    await clearTransaction(WID, ID)
    await expect(deleteTransaction(WID, ID)).resolves.toBeUndefined()

    expect(api.calls.map((call) => `${call.method} ${call.path.replace(WID, 'W').replace(ID, 'ID')}`)).toEqual([
      'GET /api/v1/workspaces/W/transactions?page=2&size=20&type=INCOME',
      'GET /api/v1/workspaces/W/transactions/ID',
      'POST /api/v1/workspaces/W/transactions',
      'PUT /api/v1/workspaces/W/transactions/ID',
      'POST /api/v1/workspaces/W/transactions/ID/clear',
      'DELETE /api/v1/workspaces/W/transactions/ID',
    ])
    expect(api.calls[2].body).toEqual({ type: 'EXPENSE', accountId: 'a', amountCents: 3590, occurredOn: '2026-11-11', description: 'Almoço', categoryId: 'c', status: 'PENDING' })
    expect(api.calls[3].body).toEqual({ accountId: 'a', amountCents: 100, occurredOn: '2026-11-11', description: 'x', status: 'CLEARED' })
    expect(api.calls[4].body).toBeUndefined()
    expect(api.calls[5].body).toBeUndefined()
  })

  it('o identificador é codificado no caminho', async () => {
    const api = fakeApi({ [`GET /api/v1/workspaces/${encodeURIComponent('a/b')}/transactions/${encodeURIComponent('x/y')}`]: ok(tx) })

    await getTransaction('a/b', 'x/y')

    expect(api.calls[0].path).toBe('/api/v1/workspaces/a%2Fb/transactions/x%2Fy')
  })

  it('nenhuma chamada leva credencial no endereço nem cabeçalho de credencial, e todas vão com o cookie', async () => {
    const api = fakeApi({ [wsRoute('GET', WID, '/transactions')]: ok(page) })

    await listTransactions(WID, 1, { q: 'texto livre', from: '2026-01-01' })

    const [call] = api.calls
    expect(Object.keys(call.headers).some((name) => name.startsWith('auth'))).toBe(false)
    expect(call.credentials).toBe('same-origin')
    expect([...new URLSearchParams(call.path.split('?')[1]).keys()]).toEqual(['page', 'size', 'from', 'q'])
  })
})
