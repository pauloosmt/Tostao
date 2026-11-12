import { describe, expect, it } from 'vitest'
import { buildCreateRecurrence, daysBefore, emptyRecurrenceForm, frequencyWord, validateRecurrenceForm } from './recurrenceForm'

const TODAY = '2026-11-12'
const form = (extra: Partial<ReturnType<typeof emptyRecurrenceForm>> = {}) => ({ ...emptyRecurrenceForm('EXPENSE', TODAY), amount: '1.200,00', description: ' Aluguel ', accountId: 'a1', categoryId: 'c1', ...extra })

describe('a validação', () => {
  it('um formulário completo não tem erro; valor, descrição, conta e categoria pedem o seu', () => {
    expect(validateRecurrenceForm(form(), TODAY)).toEqual({})
    expect(Object.keys(validateRecurrenceForm(emptyRecurrenceForm('EXPENSE', TODAY), TODAY)).sort()).toEqual(['account', 'amount', 'category', 'description'])
  })

  it('o início vai até 366 dias atrás; o fim não pode ser antes do início nem depois de 2100', () => {
    expect(daysBefore(TODAY, 366)).toBe('2025-11-11')
    expect(validateRecurrenceForm(form({ date: '2025-11-11' }), TODAY).date).toBeUndefined()
    expect(validateRecurrenceForm(form({ date: '2025-11-10' }), TODAY).date).toBe('Use uma data de até 366 dias atrás.')
    expect(validateRecurrenceForm(form({ endOn: '2026-11-01' }), TODAY).endOn).toBe('O fim não pode ser antes do início.')
    expect(validateRecurrenceForm(form({ endOn: '2101-01-01' }), TODAY).endOn).toMatch(/2000 a 2100/)
    expect(validateRecurrenceForm(form({ endOn: '2027-11-12' }), TODAY).endOn).toBeUndefined()
  })

  it('a transferência pede o destino, diferente da origem, e não pede categoria', () => {
    const transfer = form({ type: 'TRANSFER', categoryId: '', destinationAccountId: '' })
    expect(validateRecurrenceForm(transfer, TODAY)).toEqual({ destination: 'Escolha a conta de destino.' })
    expect(validateRecurrenceForm({ ...transfer, destinationAccountId: 'a1' }, TODAY).destination).toMatch(/diferentes/)
  })
})

describe('o corpo do pedido', () => {
  it('despesa: valor positivo em centavos, categoria, frequência e início; sem fim nem efetivação automática por padrão', () => {
    expect(buildCreateRecurrence(form())).toEqual({ accountId: 'a1', categoryId: 'c1', type: 'EXPENSE', amountCents: 120000, description: 'Aluguel', frequency: 'MONTHLY', startOn: TODAY })
  })

  it('fim, observações e "efetivar sozinhas" só quando há; a transferência envia o destino e não a categoria', () => {
    expect(buildCreateRecurrence(form({ endOn: '2027-11-12', notes: ' contrato ', autoClear: true, frequency: 'YEARLY' }))).toMatchObject({ endOn: '2027-11-12', notes: 'contrato', autoClear: true, frequency: 'YEARLY' })
    const transfer = buildCreateRecurrence(form({ type: 'TRANSFER', categoryId: 'c1', destinationAccountId: 'a2' }))
    expect(transfer).toMatchObject({ type: 'TRANSFER', destinationAccountId: 'a2' })
    expect(transfer).not.toHaveProperty('categoryId')
  })

  it('as frequências em palavras', () => {
    expect([frequencyWord('WEEKLY'), frequencyWord('MONTHLY'), frequencyWord('YEARLY')]).toEqual(['Toda semana', 'Todo mês', 'Todo ano'])
  })
})
