import { describe, expect, it } from 'vitest'
import { fakeApi, ok, wsRoute } from '../test/fakeApi'
import * as accounts from './accounts'
import * as categories from './categories'
import type { Account, Category } from './types'

const WID = '11111111-1111-1111-1111-111111111111'
const ID = '22222222-2222-2222-2222-222222222222'
const account = { id: ID, name: 'Carteira' } as Account
const category = { id: ID, name: 'Mercado' } as Category

describe('contas', () => {
  it('lista as ativas por padrão e as arquivadas também só quando pedido', async () => {
    const api = fakeApi({ [wsRoute('GET', WID, '/accounts')]: ok([account]) })

    await accounts.listAccounts(WID)
    await accounts.listAccounts(WID, true)

    expect(api.calls.map((call) => call.path)).toEqual([`/api/v1/workspaces/${WID}/accounts`, `/api/v1/workspaces/${WID}/accounts?includeArchived=true`])
  })

  it('cada chamada usa o método e o caminho do contrato, no workspace dado, com o corpo exato', async () => {
    const api = fakeApi({
      [wsRoute('GET', WID, `/accounts/${ID}`)]: ok(account),
      [wsRoute('POST', WID, '/accounts')]: ok(account, 201),
      [wsRoute('PUT', WID, `/accounts/${ID}`)]: ok(account),
      [wsRoute('POST', WID, `/accounts/${ID}/archive`)]: ok(account),
      [wsRoute('POST', WID, `/accounts/${ID}/restore`)]: ok(account),
      [wsRoute('GET', WID, '/reports/balance')]: ok({ accounts: [], totalBalanceCents: 0 }),
    })

    await accounts.getAccount(WID, ID)
    await accounts.createAccount(WID, { name: 'Cartão', type: 'CREDIT_CARD', initialBalanceCents: -12345, creditLimitCents: 500000, closingDay: 10, dueDay: 17 })
    await accounts.updateAccount(WID, ID, { name: 'Novo nome', initialBalanceCents: 100 })
    await accounts.archiveAccount(WID, ID)
    await accounts.restoreAccount(WID, ID)
    await accounts.balanceReport(WID)

    expect(api.calls.map((call) => `${call.method} ${call.path.replace(WID, 'W').replace(ID, 'ID')}`)).toEqual([
      'GET /api/v1/workspaces/W/accounts/ID',
      'POST /api/v1/workspaces/W/accounts',
      'PUT /api/v1/workspaces/W/accounts/ID',
      'POST /api/v1/workspaces/W/accounts/ID/archive',
      'POST /api/v1/workspaces/W/accounts/ID/restore',
      'GET /api/v1/workspaces/W/reports/balance',
    ])
    expect(api.calls[1].body).toEqual({ name: 'Cartão', type: 'CREDIT_CARD', initialBalanceCents: -12345, creditLimitCents: 500000, closingDay: 10, dueDay: 17 })
    expect(api.calls[2].body).toEqual({ name: 'Novo nome', initialBalanceCents: 100 })
    expect(api.calls[3].body).toBeUndefined()
    expect(api.calls[4].body).toBeUndefined()
  })
})

describe('categorias', () => {
  it('lista as ativas por padrão e as arquivadas também só quando pedido', async () => {
    const api = fakeApi({ [wsRoute('GET', WID, '/categories')]: ok([]) })

    await categories.listCategories(WID)
    await categories.listCategories(WID, true)

    expect(api.calls.map((call) => call.path)).toEqual([`/api/v1/workspaces/${WID}/categories`, `/api/v1/workspaces/${WID}/categories?includeArchived=true`])
  })

  it('cada chamada usa o método e o caminho do contrato, no workspace dado, com o corpo exato', async () => {
    const api = fakeApi({
      [wsRoute('GET', WID, `/categories/${ID}`)]: ok(category),
      [wsRoute('POST', WID, '/categories')]: ok(category, 201),
      [wsRoute('PUT', WID, `/categories/${ID}`)]: ok(category),
      [wsRoute('POST', WID, `/categories/${ID}/archive`)]: ok(category),
      [wsRoute('POST', WID, `/categories/${ID}/restore`)]: ok(category),
    })

    await categories.getCategory(WID, ID)
    await categories.createCategory(WID, { name: 'Mercado', parentId: ID, color: '#1A9E5C' })
    await categories.updateCategory(WID, ID, { name: 'Supermercado', color: '#2F6FDE', icon: 'cart' })
    await categories.archiveCategory(WID, ID)
    await categories.restoreCategory(WID, ID)

    expect(api.calls.map((call) => `${call.method} ${call.path.replace(WID, 'W').replace(ID, 'ID')}`)).toEqual([
      'GET /api/v1/workspaces/W/categories/ID',
      'POST /api/v1/workspaces/W/categories',
      'PUT /api/v1/workspaces/W/categories/ID',
      'POST /api/v1/workspaces/W/categories/ID/archive',
      'POST /api/v1/workspaces/W/categories/ID/restore',
    ])
    expect(api.calls[1].body).toEqual({ name: 'Mercado', parentId: ID, color: '#1A9E5C' })
    expect(api.calls[2].body).toEqual({ name: 'Supermercado', color: '#2F6FDE', icon: 'cart' })
  })
})

describe('o que as chamadas nunca fazem', () => {
  it('o identificador é codificado no caminho (nada de ../ ou barras vindas de fora)', async () => {
    const api = fakeApi({ [`GET /api/v1/workspaces/${encodeURIComponent('a/../b')}/accounts/${encodeURIComponent('x/y')}`]: ok(account) })

    await accounts.getAccount('a/../b', 'x/y')

    expect(api.calls[0].path).toBe('/api/v1/workspaces/a%2F..%2Fb/accounts/x%2Fy')
  })

  it('nenhuma chamada leva credencial nem nada além do includeArchived na consulta do endereço', async () => {
    const api = fakeApi({
      [wsRoute('GET', WID, '/accounts')]: ok([]),
      [wsRoute('GET', WID, '/categories')]: ok([]),
      [wsRoute('GET', WID, '/reports/balance')]: ok({ accounts: [], totalBalanceCents: 0 }),
    })

    await accounts.listAccounts(WID, true)
    await categories.listCategories(WID, true)
    await accounts.balanceReport(WID)

    for (const call of api.calls) {
      const query = call.path.split('?')[1] ?? ''
      expect(query === '' || query === 'includeArchived=true', call.path).toBe(true)
      expect(Object.keys(call.headers).some((name) => name.startsWith('auth'))).toBe(false)
      expect(call.credentials).toBe('same-origin')
    }
  })
})
