import type { Account } from '../api/types'
import { ok, wsRoute } from './fakeApi'
import { WID, makeAccount, makeBalanceReport } from './fixtures'

/** As duas consultas do Início (as contas e o saldo), para os testes que abrem o aplicativo na tela inicial. */
export function homeRoutes(accounts: Account[] = [makeAccount({ name: 'Carteira', type: 'CASH', balanceCents: 15000 })], workspaceId: string = WID) {
  return {
    [wsRoute('GET', workspaceId, '/accounts')]: ok(accounts),
    [wsRoute('GET', workspaceId, '/reports/balance')]: ok(makeBalanceReport(accounts)),
  }
}
