import { describe, expect, it } from 'vitest'
import { buildInstallmentPurchase, describeSplit, installmentDates, parseInstallmentCount, splitCents, validateInstallments } from './installmentForm'
import { emptyTransactionForm } from './transactionForm'

const form = (extra: Partial<ReturnType<typeof emptyTransactionForm>> = {}) => ({
  ...emptyTransactionForm('EXPENSE', '2026-11-12'),
  amount: '1.000,00',
  description: '  Geladeira  ',
  accountId: 'a1',
  categoryId: 'c1',
  ...extra,
})

describe('a divisão do total', () => {
  it('em centavos exatos: o resto vai, um centavo por parcela, às primeiras; a soma é sempre o total', () => {
    expect(splitCents(100000, 3)).toEqual([33334, 33333, 33333])
    expect(splitCents(100000, 4)).toEqual([25000, 25000, 25000, 25000])
    expect(splitCents(10, 4)).toEqual([3, 3, 2, 2])
    expect(splitCents(2, 2)).toEqual([1, 1])
    for (const [total, count] of [[99999, 7], [1, 1], [123457, 48]] as const) expect(splitCents(total, count).reduce((a, b) => a + b, 0)).toBe(total)
  })

  it('o resumo em palavras', () => {
    expect(describeSplit(30000, 3)).toMatch(/^3 parcelas de R\$\s*100,00$/)
    expect(describeSplit(100000, 3)).toMatch(/^3 parcelas: 1 de R\$\s*333,34 e 2 de R\$\s*333,33$/)
  })
})

describe('as datas das parcelas', () => {
  it('mensais a partir da primeira, com o dia limitado ao fim do mês, sem acumular', () => {
    expect(installmentDates('2026-01-31', 4)).toEqual(['2026-01-31', '2026-02-28', '2026-03-31', '2026-04-30'])
    expect(installmentDates('2024-01-31', 2)).toEqual(['2024-01-31', '2024-02-29']) // ano bissexto
    expect(installmentDates('2026-11-12', 3)).toEqual(['2026-11-12', '2026-12-12', '2027-01-12'])
  })
})

describe('a validação', () => {
  it('o número de parcelas é um inteiro de 2 a 48', () => {
    expect(parseInstallmentCount('2')).toBe(2)
    expect(parseInstallmentCount(' 48 ')).toBe(48)
    for (const text of ['', '1', '49', '2,5', '0', 'abc', '-3']) expect(parseInstallmentCount(text)).toBeUndefined()
    expect(validateInstallments(form(), '1')).toEqual({ count: 'Informe de 2 a 48 parcelas.' })
  })

  it('um centavo por parcela, a descrição base e a última parcela dentro de 2100', () => {
    expect(validateInstallments(form({ amount: '0,05' }), '10').amount).toMatch(/pelo menos 1 centavo por parcela/)
    expect(validateInstallments(form({ amount: '0,10' }), '10').amount).toBeUndefined()
    expect(validateInstallments(form({ description: 'x'.repeat(191) }), '3').description).toBe('Use no máximo 190 caracteres.')
    expect(validateInstallments(form({ date: '2100-12-01' }), '2').date).toBe('A última parcela passaria de 2100.')
    expect(validateInstallments(form(), '3')).toEqual({})
  })
})

describe('o corpo do pedido', () => {
  it('total em centavos, descrição base sem os espaços, categoria e conta; o estado só quando pendente', () => {
    expect(buildInstallmentPurchase(form(), 3)).toEqual({ accountId: 'a1', categoryId: 'c1', totalCents: 100000, installmentCount: 3, firstOccurredOn: '2026-11-12', description: 'Geladeira' })
    expect(buildInstallmentPurchase(form({ pending: true, notes: ' presente ' }), 12)).toMatchObject({ notes: 'presente', firstInstallmentStatus: 'PENDING', installmentCount: 12 })
  })
})
