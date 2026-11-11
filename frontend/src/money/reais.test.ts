import { describe, expect, it } from 'vitest'
import { MAX_CENTS, REAIS_MESSAGES, formatReaisInput, parseReais } from './reais'

const cents = (text: string) => parseReais(text)
const error = (text: string) => ('error' in parseReais(text) ? (parseReais(text) as { error: string }).error : null)

describe('o que vira centavos', () => {
  it.each([
    ['1.234,56', 123456],
    ['1234,56', 123456],
    ['R$ 1.234,56', 123456],
    ['R$1.234,56', 123456],
    ['r$ 1.234,56', 123456],
    ['12,5', 1250],
    ['12,50', 1250],
    ['0,05', 5],
    ['0,5', 50],
    ['0', 0],
    ['0,00', 0],
    ['50', 5000],
    ['1.234', 123400],
    ['1.234.567,89', 123456789],
    ['  50  ', 5000],
    [' 50,00 ', 5000],
    ['007', 700],
  ])('%s -> %i centavos', (text, expected) => {
    expect(cents(text)).toEqual({ cents: expected })
  })

  it.each([
    ['-50', -5000],
    ['−50', -5000],
    ['- 50', -5000],
    ['-R$ 50,00', -5000],
    ['R$ -50,00', -5000],
    ['-0,01', -1],
    ['-1.234,56', -123456],
  ])('%s -> %i centavos (negativo)', (text, expected) => {
    expect(cents(text)).toEqual({ cents: expected })
  })

  it('o zero negativo é zero (nunca -0)', () => {
    const result = parseReais('-0,00') as { cents: number }

    expect(Object.is(result.cents, -0)).toBe(false)
    expect(result.cents).toBe(0)
  })

  it('não erra nos centavos em que o ponto flutuante erra', () => {
    expect(cents('0,07')).toEqual({ cents: 7 })
    expect(cents('1,15')).toEqual({ cents: 115 })
    expect(cents('19,99')).toEqual({ cents: 1999 })
    expect(cents('0,29')).toEqual({ cents: 29 })
    expect(cents('4.35')).toHaveProperty('error') // (4.35 * 100 = 434.99999... em ponto flutuante; aqui nem é aceito)
  })
})

describe('o que é recusado, e com qual mensagem', () => {
  it.each(['', '   ', 'abc', 'R$', 'R$ ', '-', '--5', '- -5', '1,2,3', ',50', '12,', '1 000,00', '12a', 'R$ R$ 5', '5-', '1e3', '+5', '(5)'])(
    '%j pede o formato',
    (text) => {
      expect(error(text)).toBe(REAIS_MESSAGES.format)
    },
  )

  it.each(['12.5', '1.23', '12.34', '1234.56', '12.34,5', '1.2345', '.5', '1..234', '1.234.56'])('%j pede a vírgula para os centavos', (text) => {
    expect(error(text)).toBe(REAIS_MESSAGES.comma)
  })

  it.each(['1,234', '0,001', '12,500', '1.234,567'])('%j tem casas decimais demais (nunca arredonda em silêncio)', (text) => {
    expect(error(text)).toBe(REAIS_MESSAGES.decimals)
  })
})

describe('os limites da API', () => {
  it('aceita exatamente R$ 10.000.000.000,00 e recusa um centavo além, nos dois sinais', () => {
    expect(cents('10.000.000.000,00')).toEqual({ cents: MAX_CENTS })
    expect(cents('-10.000.000.000,00')).toEqual({ cents: -MAX_CENTS })
    expect(error('10.000.000.000,01')).toBe(REAIS_MESSAGES.max)
    expect(error('-10.000.000.000,01')).toBe(REAIS_MESSAGES.min)
  })

  it('valores enormes são recusados sem perder a precisão da conta', () => {
    expect(error('99.999.999.999.999,99')).toBe(REAIS_MESSAGES.max)
    expect(error('99999999999999999999')).toBe(REAIS_MESSAGES.max)
    expect(error('-99999999999999999999')).toBe(REAIS_MESSAGES.min)
  })

  it('zeros à esquerda não contam para o limite', () => {
    expect(cents('0000000000000000050')).toEqual({ cents: 5000 })
  })
})

describe('formatReaisInput', () => {
  it.each([
    [123456, '1.234,56'],
    [1250, '12,50'],
    [5, '0,05'],
    [0, '0,00'],
    [100, '1,00'],
    [-5000, '-50,00'],
    [-1, '-0,01'],
    [100000000, '1.000.000,00'],
    [MAX_CENTS, '10.000.000.000,00'],
    [-MAX_CENTS, '-10.000.000.000,00'],
  ])('%i -> %s', (value, expected) => {
    expect(formatReaisInput(value)).toBe(expected)
  })

  it('ida e volta: o que o campo mostra volta aos mesmos centavos, para valores variados e nos limites', () => {
    const samples = [0, 1, 9, 10, 99, 100, 101, 999, 1000, 99999, 100000, 123456, 1_000_000, 987_654_321, MAX_CENTS - 1, MAX_CENTS]
    for (let i = 0; i < 500; i++) samples.push(Math.floor(Math.random() * MAX_CENTS))
    for (const value of samples) {
      expect(cents(formatReaisInput(value)), `+${value}`).toEqual({ cents: value })
      expect(cents(formatReaisInput(-value)), `-${value}`).toEqual({ cents: value === 0 ? 0 : -value })
    }
  })
})
