// O formulário de categoria: as regras (que espelham a API; a API é a autoridade) e a montagem do corpo da requisição.
import type { Category, CategoryKind, CreateCategory, UpdateCategory } from '../api/types'
import { isCategoryIcon, suggestCategoryIcon, type CategoryIconName } from '../design-system/src'
import type { FieldMessages } from './account'

export type CategoryField = 'name' | 'kind'
export const CATEGORY_FIELD_ORDER: readonly CategoryField[] = ['name', 'kind']

/** Do nome do campo na API para o campo da tela; o resto (cor, ícone, categoria principal) vira o aviso geral. */
export const CATEGORY_API_FIELDS: Record<string, CategoryField> = { name: 'name', kind: 'kind' }

export const CATEGORY_NAME_MAX = 60

export const KIND_OPTIONS: readonly { value: CategoryKind; label: string; plural: string }[] = [
  { value: 'EXPENSE', label: 'Despesa', plural: 'Despesas' },
  { value: 'INCOME', label: 'Receita', plural: 'Receitas' },
]

/**
 * O que a pessoa escolheu: o nome, o tipo (só na principal), a cor (`#RRGGBB` ou `null`: a subcategoria usa a da mãe) e o ícone.
 * `icon`: `null` = ainda não escolheu (acompanha o nome); `false` = sem ícone (a moeda usa a inicial); ou a chave do ícone.
 */
export interface CategoryFormValues {
  name: string
  kind: CategoryKind | ''
  color: string | null
  icon: CategoryIconName | false | null
}

/** `principal`: pergunta o tipo. `sub`: o tipo é o da principal. `edit`: o tipo não muda. */
export type CategoryFormMode = 'principal' | 'sub' | 'edit'

export const emptyCategoryForm = (kind: CategoryKind | '' = '', color: string | null = null): CategoryFormValues => ({ name: '', kind, color, icon: null })

/** O ícone gravado entra como escolha; sem ícone (ou um que o app não conhece) a sugestão pelo nome continua valendo. */
export const categoryToForm = (category: Category): CategoryFormValues => ({
  name: category.name,
  kind: category.kind,
  color: category.color,
  icon: isCategoryIcon(category.icon) ? category.icon : null,
})

/** O ícone que vale: a escolha da pessoa, ou, se ainda não escolheu, o sugerido pelo nome (ou nenhum: a moeda usa a inicial). */
export function resolveIcon(picked: CategoryIconName | false | null, name: string): CategoryIconName | false {
  return picked === null ? (suggestCategoryIcon(name) ?? false) : picked
}

export function validateCategoryForm(values: CategoryFormValues, mode: CategoryFormMode): FieldMessages<CategoryField> {
  const messages: FieldMessages<CategoryField> = {}
  const name = values.name.trim()
  if (name === '') messages.name = 'Informe o nome.'
  else if (name.length > CATEGORY_NAME_MAX) messages.name = `Use no máximo ${CATEGORY_NAME_MAX} caracteres.`
  if (mode === 'principal' && values.kind === '') messages.kind = 'Escolha o tipo.'
  return messages
}

/**
 * O corpo do `POST`. A principal envia o tipo e a cor; a subcategoria envia a principal e NÃO o tipo (a API usa o da principal) nem a cor
 * (usa a da mãe). O ícone só vai quando há (sem ele a moeda usa a sugestão do nome ou a inicial). (Chamar só com o formulário já validado.)
 */
export function buildCreateCategory(values: CategoryFormValues, parentId?: string): CreateCategory {
  const body: CreateCategory = { name: values.name.trim() }
  if (parentId !== undefined) body.parentId = parentId
  else {
    body.kind = values.kind as CategoryKind
    if (values.color !== null) body.color = values.color
  }
  const icon = resolveIcon(values.icon, values.name)
  if (icon !== false) body.icon = icon
  return body
}

/**
 * O corpo do `PUT`: nome, cor (só na principal) e ícone, nunca o tipo nem a principal (não mudam). A API grava a cor e o ícone exatamente
 * como vierem, e `null` se faltarem: "sem ícone" é a ausência dele, e a subcategoria não envia cor (usa a da mãe).
 */
export function buildUpdateCategory(values: CategoryFormValues, isSub: boolean): UpdateCategory {
  const body: UpdateCategory = { name: values.name.trim() }
  if (!isSub && values.color !== null) body.color = values.color
  const icon = resolveIcon(values.icon, values.name)
  if (icon !== false) body.icon = icon
  return body
}
