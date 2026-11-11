// O `409` das categorias tem causas diferentes, e só o detalhe da API diz qual é: nome em uso (vai ao campo do nome) ou uma categoria
// arquivada no caminho (vai ao aviso geral, dizendo o que fazer).
import type { CategoryField } from '../validation/categoryForm'

export const CATEGORY_NAME_CONFLICT = 'Já existe uma categoria ativa com este nome aqui.'

export function categoryConflict(detail: string | undefined): { field?: CategoryField; message: string } {
  const text = (detail ?? '').toLowerCase()
  // (a mais específica primeiro: "A categoria principal está arquivada" também contém "está arquivada")
  if (text.includes('principal está arquivada')) return { message: 'A categoria principal está arquivada. Restaure-a antes.' }
  if (text.includes('restaure antes a categoria principal')) return { message: 'Restaure antes a categoria principal.' }
  if (text.includes('está arquivada')) return { message: 'A categoria está arquivada. Restaure-a antes de editar.' }
  return { field: 'name', message: CATEGORY_NAME_CONFLICT }
}
