// Os membros de um workspace (spec 012). Todos veem a lista; só o dono muda o papel e remove; quem não é dono pode sair (remove a si mesmo).
import { request } from './client'
import type { Member } from './types'

const base = (wid: string) => `/workspaces/${encodeURIComponent(wid)}/members`

/** Os membros, o dono primeiro. */
export const listMembers = (wid: string) => request<Member[]>('GET', base(wid))

/** Muda o papel (só `EDITOR` ou `VIEWER`; o do dono não muda). */
export const changeMemberRole = (wid: string, userId: string, role: 'EDITOR' | 'VIEWER') =>
  request<Member>('PUT', `${base(wid)}/${encodeURIComponent(userId)}`, { body: { role } })

/** O dono remove um membro; um membro que não é dono sai (remove a si mesmo). O dono não sai nem é removido. */
export const removeMember = (wid: string, userId: string) => request('DELETE', `${base(wid)}/${encodeURIComponent(userId)}`)
