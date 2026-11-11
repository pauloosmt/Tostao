// As chamadas de categorias (spec 003), no workspace atual (`wid`).
import { request } from './client'
import type { Category, CategoryTree, CreateCategory, UpdateCategory } from './types'

const base = (wid: string) => `/workspaces/${encodeURIComponent(wid)}/categories`
const one = (wid: string, id: string) => `${base(wid)}/${encodeURIComponent(id)}`

/** As categorias principais dos dois tipos, cada uma com as suas subcategorias; as arquivadas só com `includeArchived`. */
export const listCategories = (wid: string, includeArchived = false) =>
  request<CategoryTree[]>('GET', `${base(wid)}${includeArchived ? '?includeArchived=true' : ''}`)

export const getCategory = (wid: string, id: string) => request<Category>('GET', one(wid, id))

export const createCategory = (wid: string, body: CreateCategory) => request<Category>('POST', base(wid), { body })

export const updateCategory = (wid: string, id: string, body: UpdateCategory) => request<Category>('PUT', one(wid, id), { body })

/** Arquivar uma principal arquiva também as subcategorias ativas dela. */
export const archiveCategory = (wid: string, id: string) => request<Category>('POST', `${one(wid, id)}/archive`)

/** Restaurar uma principal não restaura as subcategorias. */
export const restoreCategory = (wid: string, id: string) => request<Category>('POST', `${one(wid, id)}/restore`)
