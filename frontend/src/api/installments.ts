// As compras parceladas (spec 010). Valores em centavos inteiros; a API divide o total e cria as parcelas mensais.
import { request } from './client'
import type { CancelInstallmentsResult, CreateInstallmentPurchase, InstallmentPlan } from './types'

const base = (wid: string) => `/workspaces/${encodeURIComponent(wid)}/installment-purchases`

/** Registra a compra e devolve o plano (as parcelas criadas). */
export const createInstallmentPurchase = (wid: string, body: CreateInstallmentPurchase) => request<InstallmentPlan>('POST', base(wid), { body })

export const getInstallmentPlan = (wid: string, groupId: string) => request<InstallmentPlan>('GET', `${base(wid)}/${encodeURIComponent(groupId)}`)

/** Cancela (exclui) só as parcelas ainda pendentes; sem pendentes, ou repetido, responde com zero. */
export const cancelInstallments = (wid: string, groupId: string) => request<CancelInstallmentsResult>('POST', `${base(wid)}/${encodeURIComponent(groupId)}/cancel`)
