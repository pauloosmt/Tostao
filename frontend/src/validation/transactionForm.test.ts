import { describe, expect, it } from 'vitest'
import type { Transaction } from '../api/types'
import {
  AMOUNT_MESSAGES,
  TRANSACTION_API_FIELDS,
  TRANSACTION_FIELD_ORDER,
  TRANSACTION_TYPE_OPTIONS,
  buildCreateTransaction,
  buildUpdateTransaction,
  emptyTransactionForm,
  transactionToForm,
  transferEnds,
  validateTransactionForm,
  withType,
  type TransactionFormValues,
} from './transactionForm'

const TODAY = '2026-11-11'
const expense = (extra: Partial<TransactionFormValues> = {}): TransactionFormValues => ({
  ...emptyTransactionForm('EXPENSE', TODAY),
  amount: '35,90',
  description: 'Almoço',
  accountId: 'acc-1',
  categoryId: 'cat-1',
  ...extra,
})
const transfer = (extra: Partial<TransactionFormValues> = {}): TransactionFormValues =>
  expense({ type: 'TRANSFER', categoryId: '', destinationAccountId: 'acc-2', ...extra })
const errors = (values: TransactionFormValues, mode: 'create' | 'edit' = 'create') => validateTransactionForm(values, mode)

describe('o formulário vazio', () => {
  it('nasce despesa, com a data de hoje e nada preenchido', () => {
    expect(emptyTransactionForm('EXPENSE', TODAY)).toEqual({
      type: 'EXPENSE', amount: '', date: TODAY, description: '', notes: '', accountId: '', destinationAccountId: '', categoryId: '', pending: false,
    })
  })

  it('vazio, reclama de valor, descrição, conta e categoria (a data já está preenchida)', () => {
    expect(errors(emptyTransactionForm('EXPENSE', TODAY))).toEqual({
      amount: 'Informe o valor.',
      description: 'Informe a descrição.',
      account: 'Escolha a conta.',
      category: 'Escolha a categoria.',
    })
  })

  it('os tipos oferecidos', () => {
    expect(TRANSACTION_TYPE_OPTIONS.map((o) => [o.value, o.label])).toEqual([['EXPENSE', 'Despesa'], ['INCOME', 'Receita'], ['TRANSFER', 'Transferência']])
  })
})

describe('valor', () => {
  const amount = (text: string) => errors(expense({ amount: text })).amount

  it('é obrigatório e só vale maior que zero (o sinal vem do tipo)', () => {
    expect(amount('')).toBe(AMOUNT_MESSAGES.empty)
    expect(amount('  ')).toBe(AMOUNT_MESSAGES.empty)
    expect(amount('0')).toBe(AMOUNT_MESSAGES.positive)
    expect(amount('0,00')).toBe(AMOUNT_MESSAGES.positive)
    expect(amount('-5')).toBe(AMOUNT_MESSAGES.positive)
    expect(amount('0,01')).toBeUndefined()
    expect(amount('1.234,56')).toBeUndefined()
  })

  it('passa pelas regras do campo de dinheiro: formato, casas decimais e limite', () => {
    expect(amount('abc')).toBe('Informe um valor em reais, como 1.234,56.')
    expect(amount('12.5')).toBe('Use vírgula para os centavos, por exemplo 12,50.')
    expect(amount('1,234')).toBe('Use no máximo 2 casas decimais.')
    expect(amount('10.000.000.000,00')).toBeUndefined()
    expect(amount('10.000.000.000,01')).toBe('O valor máximo é R$ 10.000.000.000,00.')
  })
})

describe('data', () => {
  const date = (text: string) => errors(expense({ date: text })).date

  it('pelas regras de data: existe, ano de 2000 a 2100', () => {
    expect(date('')).toBe('Informe a data.')
    expect(date('2026-02-30')).toBe('Informe uma data válida.')
    expect(date('1999-12-31')).toBe('Use um ano de 2000 a 2100.')
    expect(date('2101-01-01')).toBe('Use um ano de 2000 a 2100.')
    expect(date('2026-11-11')).toBeUndefined()
    expect(date('2099-01-01')).toBeUndefined() // uma data futura vale
  })
})

describe('descrição e observações', () => {
  it('descrição de 1 a 200 caracteres, sem contar os espaços das pontas', () => {
    const description = (text: string) => errors(expense({ description: text })).description

    expect(description('')).toBe('Informe a descrição.')
    expect(description('   ')).toBe('Informe a descrição.')
    expect(description('a'.repeat(200))).toBeUndefined()
    expect(description(' ' + 'a'.repeat(200) + ' ')).toBeUndefined()
    expect(description('a'.repeat(201))).toBe('Use no máximo 200 caracteres.')
  })

  it('observações opcionais, até 1.000 caracteres', () => {
    const notes = (text: string) => errors(expense({ notes: text })).notes

    expect(notes('')).toBeUndefined()
    expect(notes('a'.repeat(1000))).toBeUndefined()
    expect(notes('a'.repeat(1001))).toBe('Use no máximo 1.000 caracteres.')
  })
})

describe('conta e categoria (receita e despesa)', () => {
  it('pedem a conta e a categoria; o destino não existe', () => {
    expect(errors(expense({ accountId: '' })).account).toBe('Escolha a conta.')
    expect(errors(expense({ categoryId: '' })).category).toBe('Escolha a categoria.')
    expect(errors(expense({ destinationAccountId: 'lixo' })).destination).toBeUndefined()
  })
})

describe('transferência', () => {
  it('pede origem e destino, e não pede categoria', () => {
    expect(errors(transfer({ accountId: '', destinationAccountId: '' }))).toEqual({ account: 'Escolha a conta de origem.', destination: 'Escolha a conta de destino.' })
    expect(errors(transfer({ categoryId: '' })).category).toBeUndefined()
  })

  it('origem igual ao destino é recusada no campo do destino', () => {
    expect(errors(transfer({ accountId: 'acc-1', destinationAccountId: 'acc-1' }))).toEqual({ destination: 'A origem e o destino precisam ser diferentes.' })
  })

  it('válida não tem erro', () => {
    expect(errors(transfer())).toEqual({})
  })
})

describe('o tipo', () => {
  it('é obrigatório na criação e não é validado na edição', () => {
    expect(errors(expense({ type: '' })).type).toBe('Escolha o tipo.')
    expect(errors(expense({ type: '' }), 'edit').type).toBeUndefined()
  })

  it('a ordem dos campos na tela e o mapa dos campos da API', () => {
    expect(TRANSACTION_FIELD_ORDER).toEqual(['type', 'amount', 'date', 'description', 'account', 'destination', 'category', 'notes'])
    expect(TRANSACTION_API_FIELDS).toEqual({
      type: 'type', amountCents: 'amount', occurredOn: 'date', description: 'description', notes: 'notes', accountId: 'account', destinationAccountId: 'destination', categoryId: 'category',
    })
  })
})

describe('trocar o tipo na criação', () => {
  const fits = (id: string, kind: string) => (kind === 'EXPENSE' ? id === 'cat-despesa' : id === 'cat-receita')

  it('para transferência, descarta a categoria e mantém o resto', () => {
    const next = withType(expense({ categoryId: 'cat-despesa' }), 'TRANSFER', fits)

    expect(next).toMatchObject({ type: 'TRANSFER', categoryId: '', amount: '35,90', description: 'Almoço', accountId: 'acc-1' })
  })

  it('da transferência para despesa ou receita, descarta o destino e a categoria volta a ser pedida', () => {
    const next = withType(transfer(), 'INCOME', fits)

    expect(next).toMatchObject({ type: 'INCOME', destinationAccountId: '', categoryId: '' })
    expect(errors(next).category).toBe('Escolha a categoria.')
  })

  it('de despesa para receita, a categoria só fica se servir ao novo tipo', () => {
    expect(withType(expense({ categoryId: 'cat-despesa' }), 'INCOME', fits).categoryId).toBe('')
    expect(withType(expense({ categoryId: 'cat-receita' }), 'INCOME', fits).categoryId).toBe('cat-receita')
  })

  it('sem categoria escolhida, nada a conferir', () => {
    expect(withType(expense({ categoryId: '' }), 'INCOME', () => true).categoryId).toBe('')
  })
})

describe('o corpo do POST', () => {
  it('despesa: centavos inteiros positivos, descrição sem espaços, categoria, e nenhum destino nem estado', () => {
    const body = buildCreateTransaction(expense({ amount: ' 1.234,56 ', description: '  Almoço  ', date: ' 2026-11-11 ' }))

    expect(body).toEqual({ type: 'EXPENSE', accountId: 'acc-1', amountCents: 123456, occurredOn: '2026-11-11', description: 'Almoço', categoryId: 'cat-1' })
    expect('status' in body).toBe(false)
    expect('destinationAccountId' in body).toBe(false)
    expect('notes' in body).toBe(false)
  })

  it('o valor é sempre positivo, em centavos exatos', () => {
    expect(buildCreateTransaction(expense({ amount: '0,07' })).amountCents).toBe(7)
    expect(buildCreateTransaction(expense({ amount: '19,99' })).amountCents).toBe(1999)
    expect(buildCreateTransaction(expense({ amount: '10.000.000.000,00' })).amountCents).toBe(1_000_000_000_000)
  })

  it('receita leva a categoria; a transferência leva origem e destino e NUNCA a categoria, mesmo que sobre texto nela', () => {
    expect(buildCreateTransaction(expense({ type: 'INCOME' })).type).toBe('INCOME')

    const body = buildCreateTransaction(transfer({ categoryId: 'sobrou' }))
    expect(body).toEqual({ type: 'TRANSFER', accountId: 'acc-1', destinationAccountId: 'acc-2', amountCents: 3590, occurredOn: TODAY, description: 'Almoço' })
    expect('categoryId' in body).toBe(false)
  })

  it('despesa e receita nunca levam destino, mesmo que sobre texto nele', () => {
    expect('destinationAccountId' in buildCreateTransaction(expense({ destinationAccountId: 'sobrou' }))).toBe(false)
  })

  it('pendente envia o estado; efetivado não envia nada', () => {
    expect(buildCreateTransaction(expense({ pending: true })).status).toBe('PENDING')
    expect('status' in buildCreateTransaction(expense({ pending: false }))).toBe(false)
  })

  it('observações só vão se houver (sem os espaços das pontas)', () => {
    expect(buildCreateTransaction(expense({ notes: '  paguei no pix  ' })).notes).toBe('paguei no pix')
    expect('notes' in buildCreateTransaction(expense({ notes: '   ' }))).toBe(false)
  })
})

describe('o corpo do PUT', () => {
  it('nunca leva o tipo e sempre leva o estado (efetivado ou pendente)', () => {
    const cleared = buildUpdateTransaction(expense({ pending: false }))
    const pending = buildUpdateTransaction(expense({ pending: true }))

    expect('type' in cleared).toBe(false)
    expect(cleared.status).toBe('CLEARED')
    expect(pending.status).toBe('PENDING')
    expect(cleared).toEqual({ accountId: 'acc-1', amountCents: 3590, occurredOn: TODAY, description: 'Almoço', categoryId: 'cat-1', status: 'CLEARED' })
  })

  it('a transferência leva origem (accountId) e destino, sem categoria', () => {
    const body = buildUpdateTransaction(transfer())

    expect(body).toEqual({ accountId: 'acc-1', destinationAccountId: 'acc-2', amountCents: 3590, occurredOn: TODAY, description: 'Almoço', status: 'CLEARED' })
    expect('categoryId' in body).toBe(false)
  })

  it('observações vazias não vão (a edição é completa: ausente limpa)', () => {
    expect('notes' in buildUpdateTransaction(expense({ notes: '' }))).toBe(false)
  })
})

const stored = (extra: Partial<Transaction>): Transaction => ({
  id: 't1', accountId: 'acc-1', type: 'EXPENSE', amountCents: -3590, occurredOn: '2026-11-03', description: 'Almoço', notes: null, categoryId: 'cat-1',
  transferGroupId: null, counterpartAccountId: null, createdBy: 'u', createdAt: '', updatedAt: '', status: 'CLEARED',
  installmentGroupId: null, installmentNumber: null, installmentCount: null, autoClear: false, recurrenceId: null, occurrenceNumber: null, imported: false,
  ...extra,
})

describe('transferEnds: origem e destino por qualquer ponta', () => {
  it('na ponta de saída (valor negativo), a conta é a origem e a outra é o destino', () => {
    expect(transferEnds({ amountCents: -10000, accountId: 'A', counterpartAccountId: 'B' })).toEqual({ origin: 'A', destination: 'B' })
  })

  it('na ponta de entrada (valor positivo), a conta é o destino e a outra é a origem', () => {
    expect(transferEnds({ amountCents: 10000, accountId: 'B', counterpartAccountId: 'A' })).toEqual({ origin: 'A', destination: 'B' })
  })

  it('as duas pontas da mesma transferência dão o mesmo par', () => {
    const out = transferEnds({ amountCents: -500, accountId: 'A', counterpartAccountId: 'B' })
    const into = transferEnds({ amountCents: 500, accountId: 'B', counterpartAccountId: 'A' })

    expect(out).toEqual(into)
  })

  it('sem a outra ponta (não deveria ocorrer), não quebra', () => {
    expect(transferEnds({ amountCents: -1, accountId: 'A', counterpartAccountId: null })).toEqual({ origin: 'A', destination: '' })
  })
})

describe('do lançamento para o formulário', () => {
  it('despesa: valor positivo no formato do campo, conta e categoria', () => {
    expect(transactionToForm(stored({}))).toEqual({
      type: 'EXPENSE', amount: '35,90', date: '2026-11-03', description: 'Almoço', notes: '', accountId: 'acc-1', destinationAccountId: '', categoryId: 'cat-1', pending: false,
    })
  })

  it('receita e pendente', () => {
    const form = transactionToForm(stored({ type: 'INCOME', amountCents: 150000, status: 'PENDING', notes: 'pix' }))

    expect(form).toMatchObject({ type: 'INCOME', amount: '1.500,00', pending: true, notes: 'pix' })
  })

  it('sem categoria (nula) fica vazia', () => {
    expect(transactionToForm(stored({ categoryId: null })).categoryId).toBe('')
  })

  it('transferência pela ponta de destino: origem e destino certos, sem categoria', () => {
    const form = transactionToForm(stored({ type: 'TRANSFER', accountId: 'B', counterpartAccountId: 'A', amountCents: 10000, categoryId: null, transferGroupId: 'g' }))

    expect(form).toMatchObject({ type: 'TRANSFER', accountId: 'A', destinationAccountId: 'B', categoryId: '', amount: '100,00' })
  })

  it('ida e volta: editar sem mexer reenvia os mesmos dados e o formulário é válido', () => {
    const tx = stored({ notes: 'obs', status: 'PENDING' })
    const form = transactionToForm(tx)

    expect(validateTransactionForm(form, 'edit')).toEqual({})
    expect(buildUpdateTransaction(form)).toEqual({ accountId: 'acc-1', amountCents: 3590, occurredOn: '2026-11-03', description: 'Almoço', notes: 'obs', categoryId: 'cat-1', status: 'PENDING' })
  })

  it('ida e volta de uma transferência pela ponta de destino reenvia origem e destino na ordem certa', () => {
    const into = stored({ type: 'TRANSFER', accountId: 'B', counterpartAccountId: 'A', amountCents: 10000, categoryId: null })
    const out = stored({ type: 'TRANSFER', accountId: 'A', counterpartAccountId: 'B', amountCents: -10000, categoryId: null })

    expect(buildUpdateTransaction(transactionToForm(into))).toEqual(buildUpdateTransaction(transactionToForm(out)))
    expect(buildUpdateTransaction(transactionToForm(into))).toMatchObject({ accountId: 'A', destinationAccountId: 'B' })
  })
})
