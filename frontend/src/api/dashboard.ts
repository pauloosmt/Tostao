// O painel do mês (spec 005). Só leitura. Valores em centavos inteiros; receitas e despesas saem positivas.
import { request } from './client'
import type { Dashboard } from './types'

/** O painel do mês `AAAA-MM` do workspace. */
export const getDashboard = (wid: string, month: string) =>
  request<Dashboard>('GET', `/workspaces/${encodeURIComponent(wid)}/dashboard?month=${encodeURIComponent(month)}`)
