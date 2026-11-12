// Os convites (spec 012). Não há envio de e-mail: o código aparece uma vez, na criação, e o dono o combina com a pessoa por outro meio.
import { request } from './client'
import type { AcceptedInvitation, Invitation, InvitationCreated } from './types'

const base = (wid: string) => `/workspaces/${encodeURIComponent(wid)}/invitations`

/** Os convites pendentes (só o dono os vê). Nunca trazem o código. */
export const listInvitations = (wid: string) => request<Invitation[]>('GET', base(wid))

/** Cria o convite. A resposta é a única que traz o `code`. */
export const createInvitation = (wid: string, body: { email: string; role: 'EDITOR' | 'VIEWER' }) => request<InvitationCreated>('POST', base(wid), { body })

export const revokeInvitation = (wid: string, id: string) => request('DELETE', `${base(wid)}/${encodeURIComponent(id)}`)

/** Aceita um convite pelo código; a pessoa entra no workspace com o papel do convite. */
export const acceptInvitation = (code: string) => request<AcceptedInvitation>('POST', '/invitations/accept', { body: { code } })
