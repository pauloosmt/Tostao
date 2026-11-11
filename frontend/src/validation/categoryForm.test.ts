import { describe, expect, it } from 'vitest'
import { makeCategory } from '../test/fixtures'
import {
  CATEGORY_API_FIELDS,
  KIND_OPTIONS,
  buildCreateCategory,
  buildUpdateCategory,
  categoryToForm,
  emptyCategoryForm,
  validateCategoryForm,
} from './categoryForm'

describe('nome', () => {
  it('1 a 60 caracteres, sem contar os espaços das pontas', () => {
    const name = (value: string) => validateCategoryForm({ ...emptyCategoryForm('EXPENSE'), name: value }, 'principal').name

    expect(name('')).toBe('Informe o nome.')
    expect(name('   ')).toBe('Informe o nome.')
    expect(name('a')).toBeUndefined()
    expect(name('a'.repeat(60))).toBeUndefined()
    expect(name(' ' + 'a'.repeat(60) + ' ')).toBeUndefined()
    expect(name('a'.repeat(61))).toBe('Use no máximo 60 caracteres.')
  })
})

describe('tipo', () => {
  it('a principal precisa de tipo; a subcategoria e a edição não o perguntam', () => {
    const values = { ...emptyCategoryForm(), name: 'Mercado' }

    expect(validateCategoryForm(values, 'principal')).toEqual({ kind: 'Escolha o tipo.' })
    expect(validateCategoryForm(values, 'sub')).toEqual({})
    expect(validateCategoryForm(values, 'edit')).toEqual({})
    expect(validateCategoryForm({ ...values, kind: 'INCOME' }, 'principal')).toEqual({})
  })

  it('os tipos são despesa e receita, com o plural da lista', () => {
    expect(KIND_OPTIONS.map((o) => [o.value, o.label, o.plural])).toEqual([
      ['EXPENSE', 'Despesa', 'Despesas'],
      ['INCOME', 'Receita', 'Receitas'],
    ])
  })

  it('o formulário vazio pode já nascer no tipo que estava aberto na lista', () => {
    expect(emptyCategoryForm()).toEqual({ name: '', kind: '', color: null })
    expect(emptyCategoryForm('INCOME')).toEqual({ name: '', kind: 'INCOME', color: null })
  })
})

describe('o corpo do POST', () => {
  it('a principal envia o tipo e não envia a principal', () => {
    expect(buildCreateCategory({ name: '  Alimentação  ', kind: 'EXPENSE', color: '#1A9E5C' })).toEqual({ name: 'Alimentação', kind: 'EXPENSE', color: '#1A9E5C' })
  })

  it('a subcategoria envia a principal e NÃO envia o tipo (a API usa o da principal), mesmo com o tipo preenchido', () => {
    const body = buildCreateCategory({ name: 'Mercado', kind: 'EXPENSE', color: null }, 'parent-1')

    expect(body).toEqual({ name: 'Mercado', parentId: 'parent-1' })
    expect('kind' in body).toBe(false)
  })

  it('sem cor, nenhuma cor é enviada', () => {
    const body = buildCreateCategory({ name: 'Salário', kind: 'INCOME', color: null })

    expect(body).toEqual({ name: 'Salário', kind: 'INCOME' })
    expect('color' in body).toBe(false)
  })

  it('nunca envia ícone na criação (não há edição de ícone)', () => {
    expect('icon' in buildCreateCategory({ name: 'A', kind: 'EXPENSE', color: '#1A9E5C' })).toBe(false)
  })
})

describe('o corpo do PUT', () => {
  it('envia só nome, cor e o ícone atual; nunca o tipo nem a principal', () => {
    const body = buildUpdateCategory({ name: ' Supermercado ', kind: 'EXPENSE', color: '#2F6FDE' }, 'cart')

    expect(body).toEqual({ name: 'Supermercado', color: '#2F6FDE', icon: 'cart' })
    expect('kind' in body).toBe(false)
    expect('parentId' in body).toBe(false)
  })

  it('o ícone existente é sempre reenviado (a API grava o que vier; omiti-lo o apagaria)', () => {
    expect(buildUpdateCategory({ name: 'A', kind: 'EXPENSE', color: null }, 'tag').icon).toBe('tag')
  })

  it('sem ícone na categoria, nenhum ícone é enviado; "sem cor" é a ausência da cor', () => {
    const body = buildUpdateCategory({ name: 'A', kind: 'EXPENSE', color: null }, null)

    expect(body).toEqual({ name: 'A' })
  })

  it('uma cor que não é da paleta, mas já estava gravada, volta como está', () => {
    expect(buildUpdateCategory({ name: 'A', kind: 'EXPENSE', color: '#123456' }, null).color).toBe('#123456')
  })
})

describe('da categoria para o formulário', () => {
  it('traz nome, tipo e cor atuais', () => {
    const category = makeCategory({ name: 'Mercado', kind: 'INCOME', color: '#1a9e5c' })

    expect(categoryToForm(category)).toEqual({ name: 'Mercado', kind: 'INCOME', color: '#1a9e5c' })
  })

  it('ida e volta: editar sem mexer reenvia os mesmos dados, inclusive o ícone', () => {
    const category = makeCategory({ name: 'Mercado', color: '#1a9e5c', icon: 'cart' })

    expect(buildUpdateCategory(categoryToForm(category), category.icon)).toEqual({ name: 'Mercado', color: '#1a9e5c', icon: 'cart' })
  })
})

describe('os campos da API na tela', () => {
  it('só o nome e o tipo têm campo; o resto vai ao aviso geral', () => {
    expect(CATEGORY_API_FIELDS).toEqual({ name: 'name', kind: 'kind' })
  })
})
