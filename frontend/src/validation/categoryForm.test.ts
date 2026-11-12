import { describe, expect, it } from 'vitest'
import { makeCategory } from '../test/fixtures'
import { buildCreateCategory, buildUpdateCategory, categoryToForm, emptyCategoryForm, resolveIcon, validateCategoryForm } from './categoryForm'

const values = (extra: Partial<ReturnType<typeof emptyCategoryForm>> = {}) => ({ ...emptyCategoryForm('EXPENSE', '#4fb3b1'), name: 'Academia', ...extra })

describe('a validação', () => {
  it('o nome é obrigatório e vai até 60 caracteres; o tipo é pedido só na principal', () => {
    expect(validateCategoryForm(values({ name: '   ' }), 'principal')).toEqual({ name: 'Informe o nome.' })
    expect(validateCategoryForm(values({ name: 'x'.repeat(61) }), 'principal').name).toBe('Use no máximo 60 caracteres.')
    expect(validateCategoryForm(values({ kind: '' }), 'principal')).toEqual({ kind: 'Escolha o tipo.' })
    expect(validateCategoryForm(values({ kind: '' }), 'sub')).toEqual({})
    expect(validateCategoryForm(values(), 'principal')).toEqual({})
  })
})

describe('o ícone', () => {
  it('sem escolha acompanha o nome; a escolha da pessoa vale mesmo se o nome mudar; "sem ícone" é a inicial', () => {
    expect(resolveIcon(null, 'Academia')).toBe('dumbbell')
    expect(resolveIcon(null, 'Xodó')).toBe(false)
    expect(resolveIcon('cart', 'Academia')).toBe('cart')
    expect(resolveIcon(false, 'Academia')).toBe(false)
  })
})

describe('o corpo do POST', () => {
  it('a principal envia o tipo, a cor e o ícone sugerido pelo nome', () => {
    expect(buildCreateCategory(values({ name: '  Academia  ' }))).toEqual({ name: 'Academia', kind: 'EXPENSE', color: '#4fb3b1', icon: 'dumbbell' })
  })

  it('a subcategoria envia a mãe e NÃO o tipo nem a cor', () => {
    expect(buildCreateCategory(values({ name: 'Viagens', icon: 'plane' }), 'p1')).toEqual({ name: 'Viagens', parentId: 'p1', icon: 'plane' })
  })

  it('sem ícone (ou sem sugestão) não envia ícone; sem cor não envia cor', () => {
    expect(buildCreateCategory(values({ icon: false }))).toEqual({ name: 'Academia', kind: 'EXPENSE', color: '#4fb3b1' })
    expect(buildCreateCategory(values({ name: 'Xodó', color: null }))).toEqual({ name: 'Xodó', kind: 'EXPENSE' })
  })
})

describe('o corpo do PUT', () => {
  it('nome, cor e ícone, sem o tipo; a subcategoria não envia cor', () => {
    expect(buildUpdateCategory(values({ icon: 'bag' }), false)).toEqual({ name: 'Academia', color: '#4fb3b1', icon: 'bag' })
    expect(buildUpdateCategory(values({ icon: 'bag' }), true)).toEqual({ name: 'Academia', icon: 'bag' })
    expect(buildUpdateCategory(values({ icon: false }), false)).toEqual({ name: 'Academia', color: '#4fb3b1' })
  })
})

describe('da categoria para o formulário', () => {
  it('traz o ícone gravado como escolha; sem ícone (ou um desconhecido) volta a acompanhar o nome', () => {
    expect(categoryToForm(makeCategory({ name: 'Pets', color: '#ff8a78', icon: 'paw' }))).toMatchObject({ name: 'Pets', color: '#ff8a78', icon: 'paw' })
    expect(categoryToForm(makeCategory({ icon: null })).icon).toBeNull()
    expect(categoryToForm(makeCategory({ icon: 'qualquercoisa' })).icon).toBeNull()
  })
})
