// O formulário de categoria: as regras (que espelham a API; a API é a autoridade) e a montagem do corpo da requisição.
import type { Category, CategoryKind, CreateCategory, UpdateCategory } from '../api/types'
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

/** O que a pessoa escolheu: o nome, o tipo (só na principal) e a cor (`#RRGGBB` ou `null` para nenhuma). */
export interface CategoryFormValues {
  name: string
  kind: CategoryKind | ''
  color: string | null
}

/** `principal`: pergunta o tipo. `sub`: o tipo é o da principal. `edit`: o tipo não muda. */
export type CategoryFormMode = 'principal' | 'sub' | 'edit'

export const emptyCategoryForm = (kind: CategoryKind | '' = ''): CategoryFormValues => ({ name: '', kind, color: null })

export const categoryToForm = (category: Category): CategoryFormValues => ({ name: category.name, kind: category.kind, color: category.color })

export function validateCategoryForm(values: CategoryFormValues, mode: CategoryFormMode): FieldMessages<CategoryField> {
  const messages: FieldMessages<CategoryField> = {}
  const name = values.name.trim()
  if (name === '') messages.name = 'Informe o nome.'
  else if (name.length > CATEGORY_NAME_MAX) messages.name = `Use no máximo ${CATEGORY_NAME_MAX} caracteres.`
  if (mode === 'principal' && values.kind === '') messages.kind = 'Escolha o tipo.'
  return messages
}

/**
 * O corpo do `POST`. A principal envia o tipo; a subcategoria envia a principal e NÃO o tipo (a API usa o da principal). Sem cor, nada de
 * cor. (Chamar só com o formulário já validado.)
 */
export function buildCreateCategory(values: CategoryFormValues, parentId?: string): CreateCategory {
  const body: CreateCategory = { name: values.name.trim() }
  if (parentId !== undefined) body.parentId = parentId
  else body.kind = values.kind as CategoryKind
  if (values.color !== null) body.color = values.color
  return body
}

/**
 * O corpo do `PUT`: só nome, cor e ícone, nunca o tipo nem a principal (não mudam). A API grava a cor e o ícone exatamente como vierem,
 * e `null` se faltarem: por isso o ícone que a categoria já tinha é reenviado, e "sem cor" é a ausência da cor.
 */
export function buildUpdateCategory(values: CategoryFormValues, currentIcon: string | null): UpdateCategory {
  const body: UpdateCategory = { name: values.name.trim() }
  if (values.color !== null) body.color = values.color
  if (currentIcon !== null) body.icon = currentIcon
  return body
}
