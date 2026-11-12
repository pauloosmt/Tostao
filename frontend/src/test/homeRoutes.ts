import type { Account, Dashboard } from '../api/types'
import { ok, wsRoute } from './fakeApi'
import { WID, makeAccount, makeDashboard } from './fixtures'

/** A consulta do Início (o painel do mês), para os testes que abrem o aplicativo na tela inicial. */
export function homeRoutes(
  accounts: Account[] = [makeAccount({ name: 'Carteira', type: 'CASH', balanceCents: 15000 })],
  workspaceId: string = WID,
  dashboard: Partial<Dashboard> = {},
) {
  return { [wsRoute('GET', workspaceId, '/dashboard')]: ok(makeDashboard(accounts, dashboard)) }
}
