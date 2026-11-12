// As recorrências (specs 011 e 014): receita, despesa ou transferência que se repete. Valores em centavos inteiros e positivos (o sinal vem do tipo).
import { request } from './client'
import type { CreateRecurrence, EndRecurrenceResult, Recurrence, RecurrenceDetail } from './types'

const base = (wid: string) => `/workspaces/${encodeURIComponent(wid)}/recurrences`

export const listRecurrences = (wid: string) => request<Recurrence[]>('GET', base(wid))

/** A recorrência com as ocorrências (não excluídas) e os totais. */
export const getRecurrence = (wid: string, id: string) => request<RecurrenceDetail>('GET', `${base(wid)}/${encodeURIComponent(id)}`)

export const createRecurrence = (wid: string, body: CreateRecurrence) => request<RecurrenceDetail>('POST', base(wid), { body })

/** Encerra: exclui as ocorrências pendentes futuras; as que já ocorreram continuam. Repetido, cancela zero. */
export const endRecurrence = (wid: string, id: string) => request<EndRecurrenceResult>('POST', `${base(wid)}/${encodeURIComponent(id)}/end`)
