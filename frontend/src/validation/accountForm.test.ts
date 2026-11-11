import { describe, expect, it } from 'vitest'
import type { Account } from '../api/types'
import { ACCOUNT_API_FIELDS, accountToForm, buildCreateAccount, buildUpdateAccount, emptyAccountForm, validateAccountForm, type AccountFormValues } from './accountForm'

const form = (extra: Partial<AccountFormValues> = {}): AccountFormValues => ({ ...emptyAccountForm(), name: 'Carteira', type: 'CASH', ...extra })
const card = (extra: Partial<AccountFormValues> = {}) => form({ type: 'CREDIT_CARD', ...extra })

describe('o formulário vazio', () => {
  it('começa com saldo zero e sem tipo', () => {
    expect(emptyAccountForm()).toEqual({ name: '', type: '', balance: '0,00', limit: '', closingDay: '', dueDay: '' })
  })

  it('vazio, só o nome e o tipo reclamam', () => {
    expect(validateAccountForm(emptyAccountForm(), 'create')).toEqual({ name: 'Informe o nome.', type: 'Escolha o tipo da conta.' })
  })
})

describe('nome', () => {
  it('1 a 100 caracteres, sem contar os espaços das pontas', () => {
    expect(validateAccountForm(form({ name: '' }), 'create').name).toBe('Informe o nome.')
    expect(validateAccountForm(form({ name: '   ' }), 'create').name).toBe('Informe o nome.')
    expect(validateAccountForm(form({ name: 'a' }), 'create').name).toBeUndefined()
    expect(validateAccountForm(form({ name: 'a'.repeat(100) }), 'create').name).toBeUndefined()
    expect(validateAccountForm(form({ name: ' ' + 'a'.repeat(100) + ' ' }), 'create').name).toBeUndefined()
    expect(validateAccountForm(form({ name: 'a'.repeat(101) }), 'create').name).toBe('Use no máximo 100 caracteres.')
  })
})

describe('tipo', () => {
  it('é obrigatório na criação e não é validado na edição (vem da conta)', () => {
    expect(validateAccountForm(form({ type: '' }), 'create').type).toBe('Escolha o tipo da conta.')
    expect(validateAccountForm(form({ type: '' }), 'edit').type).toBeUndefined()
    expect(validateAccountForm(form({ type: 'SAVINGS' }), 'create').type).toBeUndefined()
  })
})

describe('saldo inicial', () => {
  it('pelo campo de dinheiro: obrigatório, formatos de reais, negativo permitido, nos limites', () => {
    expect(validateAccountForm(form({ balance: '' }), 'create').balance).toBe('Informe o saldo inicial.')
    expect(validateAccountForm(form({ balance: '  ' }), 'create').balance).toBe('Informe o saldo inicial.')
    expect(validateAccountForm(form({ balance: 'abc' }), 'create').balance).toBe('Informe um valor em reais, como 1.234,56.')
    expect(validateAccountForm(form({ balance: '12.5' }), 'create').balance).toBe('Use vírgula para os centavos, por exemplo 12,50.')
    expect(validateAccountForm(form({ balance: '1,234' }), 'create').balance).toBe('Use no máximo 2 casas decimais.')
    expect(validateAccountForm(form({ balance: '10.000.000.000,01' }), 'create').balance).toBe('O valor máximo é R$ 10.000.000.000,00.')
    for (const ok of ['0', '1.234,56', '-200,00', '-10.000.000.000,00', '10.000.000.000,00']) {
      expect(validateAccountForm(form({ balance: ok }), 'create').balance, ok).toBeUndefined()
    }
  })
})

describe('os campos do cartão', () => {
  it('só valem quando o tipo é cartão de crédito: nos outros tipos o que estiver neles é ignorado', () => {
    const junk = { limit: 'abc', closingDay: '99', dueDay: 'x' }

    expect(validateAccountForm(form({ type: 'CHECKING', ...junk }), 'create')).toEqual({})
    expect(validateAccountForm(card(junk), 'create')).toEqual({
      limit: 'Informe um valor em reais, como 1.234,56.',
      closingDay: 'Use um dia de 1 a 31.',
      dueDay: 'Use um dia de 1 a 31.',
    })
  })

  it('todos são opcionais no cartão', () => {
    expect(validateAccountForm(card(), 'create')).toEqual({})
  })

  it('o limite: zero vale, negativo não, e passa pelas regras do campo de dinheiro', () => {
    expect(validateAccountForm(card({ limit: '0' }), 'create').limit).toBeUndefined()
    expect(validateAccountForm(card({ limit: '5.000,00' }), 'create').limit).toBeUndefined()
    expect(validateAccountForm(card({ limit: '-1' }), 'create').limit).toBe('O limite não pode ser negativo.')
    expect(validateAccountForm(card({ limit: '1,234' }), 'create').limit).toBe('Use no máximo 2 casas decimais.')
    expect(validateAccountForm(card({ limit: '10.000.000.000,01' }), 'create').limit).toBe('O valor máximo é R$ 10.000.000.000,00.')
  })

  it.each([['0', false], ['1', true], ['15', true], ['31', true], ['32', false], ['-1', false], ['1.5', false], ['abc', false], ['100', false], [' 7 ', true], ['', true]] as const)(
    'o dia %j é %s',
    (day, valid) => {
      expect(validateAccountForm(card({ closingDay: day }), 'create').closingDay).toBe(valid ? undefined : 'Use um dia de 1 a 31.')
      expect(validateAccountForm(card({ dueDay: day }), 'create').dueDay).toBe(valid ? undefined : 'Use um dia de 1 a 31.')
    },
  )

  it('vencimento antes do fechamento é permitido (cada banco decide)', () => {
    expect(validateAccountForm(card({ closingDay: '25', dueDay: '5' }), 'create')).toEqual({})
  })
})

describe('o corpo do POST', () => {
  it('conta comum: nome sem os espaços das pontas, tipo e saldo em centavos inteiros, nada de cartão', () => {
    expect(buildCreateAccount(form({ name: '  Carteira  ', type: 'CASH', balance: '1.234,56' }))).toEqual({ name: 'Carteira', type: 'CASH', initialBalanceCents: 123456 })
  })

  it('conta comum nunca leva campo de cartão, mesmo com texto neles', () => {
    const body = buildCreateAccount(form({ type: 'CHECKING', limit: '5.000,00', closingDay: '10', dueDay: '17' }))

    expect(body).toEqual({ name: 'Carteira', type: 'CHECKING', initialBalanceCents: 0 })
    expect(Object.keys(body)).toEqual(['name', 'type', 'initialBalanceCents'])
  })

  it('cartão: leva só os campos de cartão preenchidos, limite em centavos e dias como números', () => {
    expect(buildCreateAccount(card({ balance: '-120,50', limit: '5.000,00', closingDay: ' 10 ', dueDay: '17' }))).toEqual({
      name: 'Carteira',
      type: 'CREDIT_CARD',
      initialBalanceCents: -12050,
      creditLimitCents: 500000,
      closingDay: 10,
      dueDay: 17,
    })
    expect(buildCreateAccount(card({ limit: '' }))).toEqual({ name: 'Carteira', type: 'CREDIT_CARD', initialBalanceCents: 0 })
    expect(buildCreateAccount(card({ limit: '0' })).creditLimitCents).toBe(0)
  })
})

describe('o corpo do PUT', () => {
  it('nunca leva o tipo, e leva os campos do cartão só se for cartão', () => {
    expect(buildUpdateAccount(form({ type: 'SAVINGS', name: ' Reserva ', balance: '100' }))).toEqual({ name: 'Reserva', initialBalanceCents: 10000 })
    expect(buildUpdateAccount(card({ limit: '1.000,00', closingDay: '5' }))).toEqual({ name: 'Carteira', initialBalanceCents: 0, creditLimitCents: 100000, closingDay: 5 })
    expect('type' in buildUpdateAccount(card())).toBe(false)
  })
})

describe('da conta para o formulário', () => {
  const account = (extra: Partial<Account>): Account => ({
    id: '1', name: 'Nubank', type: 'CREDIT_CARD', currency: 'BRL', initialBalanceCents: -123456, balanceCents: 0,
    creditLimitCents: 500000, closingDay: 10, dueDay: 17, archived: false, createdAt: '', updatedAt: '', ...extra,
  })

  it('mostra os valores no formato do campo', () => {
    expect(accountToForm(account({}))).toEqual({ name: 'Nubank', type: 'CREDIT_CARD', balance: '-1.234,56', limit: '5.000,00', closingDay: '10', dueDay: '17' })
  })

  it('o que não foi informado fica em branco', () => {
    expect(accountToForm(account({ type: 'CHECKING', creditLimitCents: null, closingDay: null, dueDay: null, initialBalanceCents: 0 }))).toEqual({
      name: 'Nubank', type: 'CHECKING', balance: '0,00', limit: '', closingDay: '', dueDay: '',
    })
  })

  it('ida e volta: o formulário de uma conta gera o corpo com os mesmos valores', () => {
    const body = buildUpdateAccount(accountToForm(account({})))

    expect(body).toEqual({ name: 'Nubank', initialBalanceCents: -123456, creditLimitCents: 500000, closingDay: 10, dueDay: 17 })
    expect(validateAccountForm(accountToForm(account({})), 'edit')).toEqual({})
  })
})

describe('os campos da API na tela', () => {
  it('cada campo da API vai ao campo certo da tela', () => {
    expect(ACCOUNT_API_FIELDS).toEqual({ name: 'name', type: 'type', initialBalanceCents: 'balance', creditLimitCents: 'limit', closingDay: 'closingDay', dueDay: 'dueDay' })
  })
})
