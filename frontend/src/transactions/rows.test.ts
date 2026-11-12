import { describe, expect, it } from 'vitest'
import type { Transaction } from '../api/types'
import { deleteText, groupByDay, tagsOf, toDisplayRow, type Names } from './rows'

const names: Names = {
  accountName: (id) => ({ a1: 'Carteira', a2: 'Reserva', a3: 'Conta antiga (arquivada)' })[id] ?? 'Conta removida',
  categoryName: (id) => ({ c1: 'Alimentação › Mercado', c2: 'Salário' })[id] ?? null,
  categoryColor: (id) => ({ c1: '#2a9d8f' })[id] ?? null,
  categoryIcon: (id) => ({ c1: 'cart' })[id] ?? null,
}

const tx = (extra: Partial<Transaction> = {}): Transaction => ({
  id: 't1', accountId: 'a1', type: 'EXPENSE', amountCents: -3590, occurredOn: '2026-11-11', description: 'Almoço', notes: null, categoryId: 'c1',
  transferGroupId: null, counterpartAccountId: null, createdBy: 'u', createdAt: '', updatedAt: '', status: 'CLEARED',
  installmentGroupId: null, installmentNumber: null, installmentCount: null, autoClear: false, recurrenceId: null, occurrenceNumber: null, imported: false,
  ...extra,
})

describe('toDisplayRow', () => {
  it('despesa: valor absoluto, tipo despesa, nomes no lugar dos identificadores', () => {
    expect(toDisplayRow(tx(), names)).toEqual({
      id: 't1', description: 'Almoço', cents: 3590, kind: 'expense', account: 'Carteira', category: 'Alimentação › Mercado', categoryColor: '#2a9d8f', categoryIcon: 'cart', date: '2026-11-11', pending: false, tags: [],
    })
  })

  it('receita: valor absoluto e tipo receita', () => {
    const row = toDisplayRow(tx({ type: 'INCOME', amountCents: 500000, categoryId: 'c2' }), names)

    expect(row).toMatchObject({ kind: 'income', cents: 500000, category: 'Salário' })
  })

  it('sem categoria (nula ou desconhecida) aparece como "Sem categoria", nunca um identificador', () => {
    expect(toDisplayRow(tx({ categoryId: null }), names).category).toBe('Sem categoria')
    expect(toDisplayRow(tx({ categoryId: 'c-que-nao-existe' }), names).category).toBe('Sem categoria')
  })

  it('o nome de uma conta arquivada continua aparecendo', () => {
    expect(toDisplayRow(tx({ accountId: 'a3' }), names).account).toBe('Conta antiga (arquivada)')
  })

  it('a saída de uma transferência diz "Para" a outra conta; a entrada diz "De"', () => {
    const out = toDisplayRow(tx({ type: 'TRANSFER', amountCents: -10000, accountId: 'a1', counterpartAccountId: 'a2', categoryId: null }), names)
    const into = toDisplayRow(tx({ type: 'TRANSFER', amountCents: 10000, accountId: 'a2', counterpartAccountId: 'a1', categoryId: null }), names)

    expect(out).toMatchObject({ kind: 'transfer', cents: 10000, account: 'Carteira', category: 'Para Reserva' })
    expect(into).toMatchObject({ kind: 'transfer', cents: 10000, account: 'Reserva', category: 'De Carteira' })
  })

  it('o pendente é marcado; um estado desconhecido não é tratado como pendente', () => {
    expect(toDisplayRow(tx({ status: 'PENDING' }), names).pending).toBe(true)
    expect(toDisplayRow(tx({ status: 'ESTRANHO' as Transaction['status'] }), names).pending).toBe(false)
  })

  it('um tipo desconhecido (outra versão da API) vira "Lançamento" no estilo de despesa, sem quebrar', () => {
    const row = toDisplayRow(tx({ type: 'FUTURO' as Transaction['type'], amountCents: 700 }), names)

    expect(row).toMatchObject({ kind: 'expense', category: 'Lançamento', cents: 700 })
  })

  it('uma transferência sem a outra ponta (não deveria ocorrer) não quebra', () => {
    const row = toDisplayRow(tx({ type: 'TRANSFER', amountCents: -1, counterpartAccountId: null }), names)

    expect(row.category).toBe('Para ')
  })

  it('o valor zero (não deveria ocorrer) não vira "-0"', () => {
    expect(Object.is(toDisplayRow(tx({ amountCents: 0 }), names).cents, -0)).toBe(false)
  })
})

describe('os selos', () => {
  it('parcela com número e total', () => {
    expect(tagsOf(tx({ installmentNumber: 2, installmentCount: 10 }))).toEqual(['Parcela 2 de 10'])
  })

  it('recorrência e importação', () => {
    expect(tagsOf(tx({ recurrenceId: 'r1', occurrenceNumber: 3 }))).toEqual(['Recorrente'])
    expect(tagsOf(tx({ imported: true }))).toEqual(['Importado'])
  })

  it('juntos, na ordem parcela, recorrente, importado', () => {
    expect(tagsOf(tx({ installmentNumber: 1, installmentCount: 3, recurrenceId: 'r', imported: true }))).toEqual(['Parcela 1 de 3', 'Recorrente', 'Importado'])
  })

  it('um lançamento comum não tem selos; parcela pela metade (só o número) não inventa selo', () => {
    expect(tagsOf(tx())).toEqual([])
    expect(tagsOf(tx({ installmentNumber: 2, installmentCount: null }))).toEqual([])
  })

  it('os selos chegam à linha', () => {
    expect(toDisplayRow(tx({ installmentNumber: 2, installmentCount: 10, imported: true }), names).tags).toEqual(['Parcela 2 de 10', 'Importado'])
  })
})

describe('groupByDay', () => {
  const row = (id: string, date: string) => toDisplayRow(tx({ id, occurredOn: date }), names)

  it('agrupa as linhas consecutivas do mesmo dia, na ordem dada', () => {
    const groups = groupByDay([row('1', '2026-11-11'), row('2', '2026-11-11'), row('3', '2026-11-10'), row('4', '2026-11-09'), row('5', '2026-11-09')])

    expect(groups.map((g) => [g.day, g.rows.map((r) => r.id)])).toEqual([
      ['2026-11-11', ['1', '2']],
      ['2026-11-10', ['3']],
      ['2026-11-09', ['4', '5']],
    ])
  })

  it('sem linhas, sem grupos; uma linha, um grupo', () => {
    expect(groupByDay([])).toEqual([])
    expect(groupByDay([row('1', '2026-11-11')])).toHaveLength(1)
  })

  it('não reordena nem junta dias separados (a ordem da API manda)', () => {
    const groups = groupByDay([row('1', '2026-11-11'), row('2', '2026-11-10'), row('3', '2026-11-11')])

    expect(groups.map((g) => g.day)).toEqual(['2026-11-11', '2026-11-10', '2026-11-11'])
  })
})

describe('deleteText', () => {
  const base = { type: 'EXPENSE' as const, installmentNumber: null, recurrenceId: null }

  it('explica o efeito conforme o tipo do lançamento', () => {
    expect(deleteText(base)).toBe('O lançamento sai da lista e do saldo da conta.')
    expect(deleteText({ ...base, type: 'TRANSFER' })).toBe('A transferência sai da lista e do saldo das duas contas.')
    expect(deleteText({ ...base, installmentNumber: 2 })).toBe('O lançamento sai da lista e do saldo da conta. Só esta parcela é excluída.')
  })
})
