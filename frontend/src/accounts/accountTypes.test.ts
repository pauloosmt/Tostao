import { describe, expect, it } from 'vitest'
import { ACCOUNT_TYPE_OPTIONS, cardNote, isCreditCard, toCardType } from './accountTypes'

describe('tipos de conta', () => {
  it('os cinco tipos da API, com o rótulo em português, na ordem do seletor', () => {
    expect(ACCOUNT_TYPE_OPTIONS.map((o) => [o.value, o.label])).toEqual([
      ['CHECKING', 'Conta corrente'],
      ['SAVINGS', 'Poupança'],
      ['CASH', 'Dinheiro'],
      ['CREDIT_CARD', 'Cartão de crédito'],
      ['INVESTMENT', 'Investimento'],
    ])
  })

  it('cada tipo da API vira o tipo do cartão do design system', () => {
    expect(ACCOUNT_TYPE_OPTIONS.map((o) => toCardType(o.value))).toEqual(['corrente', 'poupanca', 'dinheiro', 'cartao', 'investimento'])
  })

  it('um tipo desconhecido segue como está (o cartão o mostra como "Conta"), sem quebrar', () => {
    expect(toCardType('CRIPTO')).toBe('CRIPTO')
  })

  it('só o cartão de crédito é cartão', () => {
    expect(ACCOUNT_TYPE_OPTIONS.filter((o) => isCreditCard(o.value)).map((o) => o.value)).toEqual(['CREDIT_CARD'])
    expect(isCreditCard('')).toBe(false)
  })
})

describe('a linha de apoio do cartão', () => {
  type NoteInput = Parameters<typeof cardNote>[0]
  const card = (extra: Partial<NoteInput>): NoteInput => ({ type: 'CREDIT_CARD', closingDay: null, dueDay: null, creditLimitCents: null, ...extra })

  it('mostra só o que foi informado', () => {
    expect(cardNote(card({ closingDay: 10, dueDay: 17, creditLimitCents: 500000 }))).toBe('Fecha dia 10 · vence dia 17 · limite R$ 5.000,00')
    expect(cardNote(card({ closingDay: 10 }))).toBe('Fecha dia 10')
    expect(cardNote(card({ dueDay: 5 }))).toBe('vence dia 5')
    expect(cardNote(card({ creditLimitCents: 150000 }))).toBe('limite R$ 1.500,00')
    expect(cardNote(card({ closingDay: 1, creditLimitCents: 0 }))).toBe('Fecha dia 1 · limite R$ 0,00')
  })

  it('sem nada informado, não há linha (nada inventado)', () => {
    expect(cardNote(card({}))).toBeUndefined()
  })

  it('conta que não é cartão nunca tem linha, mesmo com campos de cartão preenchidos', () => {
    expect(cardNote({ type: 'CHECKING', closingDay: 10, dueDay: 17, creditLimitCents: 500000 })).toBeUndefined()
  })
})
