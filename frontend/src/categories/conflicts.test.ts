import { describe, expect, it } from 'vitest'
import { CATEGORY_NAME_CONFLICT, categoryConflict } from './conflicts'

describe('categoryConflict', () => {
  it('nome em uso (o texto da API ou qualquer outro) vai ao campo do nome', () => {
    expect(categoryConflict('Já existe uma categoria ativa com este nome neste nível')).toEqual({ field: 'name', message: CATEGORY_NAME_CONFLICT })
    expect(categoryConflict(undefined)).toEqual({ field: 'name', message: CATEGORY_NAME_CONFLICT })
    expect(categoryConflict('')).toEqual({ field: 'name', message: CATEGORY_NAME_CONFLICT })
  })

  it('a principal arquivada (ao criar uma subcategoria) vai ao aviso geral, dizendo o que fazer', () => {
    expect(categoryConflict('A categoria principal está arquivada')).toEqual({ message: 'A categoria principal está arquivada. Restaure-a antes.' })
  })

  it('restaurar uma subcategoria com a principal arquivada vai ao aviso geral', () => {
    expect(categoryConflict('Restaure antes a categoria principal')).toEqual({ message: 'Restaure antes a categoria principal.' })
  })

  it('editar uma categoria arquivada vai ao aviso geral', () => {
    expect(categoryConflict('A categoria está arquivada; restaure-a antes de editar')).toEqual({ message: 'A categoria está arquivada. Restaure-a antes de editar.' })
  })

  it('não diferencia maiúsculas', () => {
    expect(categoryConflict('A CATEGORIA PRINCIPAL ESTÁ ARQUIVADA').field).toBeUndefined()
  })
})
