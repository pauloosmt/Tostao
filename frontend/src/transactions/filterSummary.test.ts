import { describe, expect, it } from 'vitest'
import { FILTER_MESSAGES, summarize, validateFilters } from './filterSummary'
import type { Names } from './rows'

const names: Names = {
  accountName: (id) => (id === 'a1' ? 'Carteira' : 'Conta removida'),
  categoryName: (id) => (id === 'c1' ? 'Alimentação › Mercado' : null),
  categoryColor: () => null,
}

describe('summarize', () => {
  it('sem filtros, nada', () => {
    expect(summarize({}, names)).toEqual([])
  })

  it('uma etiqueta por filtro, na ordem da tela, com nomes no lugar de identificadores', () => {
    const chips = summarize({ from: '2026-11-01', to: '2026-11-30', accountId: 'a1', categoryId: 'c1', type: 'EXPENSE', status: 'PENDING', q: 'mercado' }, names)

    expect(chips.map((c) => c.label)).toEqual([
      'Período: 01/11/2026 a 30/11/2026',
      'Conta: Carteira',
      'Categoria: Alimentação › Mercado',
      'Tipo: Despesa',
      'Estado: Pendente',
      'Busca: "mercado"',
    ])
    expect(new Set(chips.map((c) => c.key)).size).toBe(6)
  })

  it('o período com só uma data', () => {
    expect(summarize({ from: '2026-11-01' }, names)[0].label).toBe('A partir de 01/11/2026')
    expect(summarize({ to: '2026-11-30' }, names)[0].label).toBe('Até 30/11/2026')
  })

  it('uma conta ou categoria que não existe mais não quebra', () => {
    expect(summarize({ accountId: 'sumiu', categoryId: 'sumiu' }, names).map((c) => c.label)).toEqual(['Conta: Conta removida', 'Categoria: removida'])
  })

  it('todos os tipos e estados têm rótulo em português', () => {
    expect(summarize({ type: 'INCOME' }, names)[0].label).toBe('Tipo: Receita')
    expect(summarize({ type: 'TRANSFER' }, names)[0].label).toBe('Tipo: Transferência')
    expect(summarize({ status: 'CLEARED' }, names)[0].label).toBe('Estado: Efetivado')
  })
})

describe('validateFilters', () => {
  const ok = { from: '', to: '', q: '' }

  it('vazio não tem erro', () => {
    expect(validateFilters(ok)).toEqual({})
  })

  it('uma data que não existe é recusada no campo certo', () => {
    expect(validateFilters({ ...ok, from: '2026-02-30' })).toEqual({ from: FILTER_MESSAGES.date })
    expect(validateFilters({ ...ok, to: 'abc' })).toEqual({ to: FILTER_MESSAGES.date })
  })

  it('a data final antes da inicial é recusada no campo da final; iguais valem', () => {
    expect(validateFilters({ ...ok, from: '2026-11-10', to: '2026-11-01' })).toEqual({ to: FILTER_MESSAGES.order })
    expect(validateFilters({ ...ok, from: '2026-11-10', to: '2026-11-10' })).toEqual({})
    expect(validateFilters({ ...ok, from: '2026-11-01', to: '2026-11-10' })).toEqual({})
  })

  it('a busca vai até 100 caracteres, sem contar os espaços das pontas', () => {
    expect(validateFilters({ ...ok, q: 'a'.repeat(100) })).toEqual({})
    expect(validateFilters({ ...ok, q: ' ' + 'a'.repeat(100) + ' ' })).toEqual({})
    expect(validateFilters({ ...ok, q: 'a'.repeat(101) })).toEqual({ q: FILTER_MESSAGES.search })
  })

  it('% e _ são caracteres comuns (valem literalmente)', () => {
    expect(validateFilters({ ...ok, q: '50%_off' })).toEqual({})
  })

  it('uma data inválida não gera também o erro de ordem', () => {
    expect(validateFilters({ ...ok, from: 'x', to: '2026-01-01' })).toEqual({ from: FILTER_MESSAGES.date })
  })
})
