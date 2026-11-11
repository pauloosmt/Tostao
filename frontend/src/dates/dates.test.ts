import { afterEach, describe, expect, it, vi } from 'vitest'
import { DATE_MESSAGES, formatDate, formatDay, parseISODate, previousDay, todayISO, toISO, validateDateText } from './dates'

afterEach(() => {
  vi.useRealTimers()
})

describe('todayISO: o dia do calendário do aparelho', () => {
  it('usa os componentes locais, com zeros à esquerda', () => {
    expect(todayISO(new Date(2026, 0, 5, 12, 0))).toBe('2026-01-05')
    expect(todayISO(new Date(2026, 10, 3))).toBe('2026-11-03')
    expect(todayISO(new Date(2026, 11, 31))).toBe('2026-12-31')
  })

  it('à noite continua sendo o mesmo dia (nada de UTC: toISOString daria o dia seguinte no Brasil)', () => {
    const lateNight = new Date(2026, 5, 14, 23, 59, 59)

    expect(todayISO(lateNight)).toBe('2026-06-14')
  })

  it('logo depois da meia-noite já é o dia novo', () => {
    expect(todayISO(new Date(2026, 5, 15, 0, 0, 1))).toBe('2026-06-15')
  })

  it('sem argumento usa o relógio de agora', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date(2027, 2, 9, 8, 0))

    expect(todayISO()).toBe('2027-03-09')
  })

  it('toISO completa ano, mês e dia', () => {
    expect(toISO(2026, 3, 7)).toBe('2026-03-07')
    expect(toISO(999, 1, 1)).toBe('0999-01-01')
  })
})

describe('parseISODate: só dias que existem', () => {
  it.each(['2026-11-03', '2000-01-01', '2100-12-31', '2024-02-29', '2000-02-29'])('%s existe', (text) => {
    expect(parseISODate(text)).not.toBeNull()
  })

  it.each(['2026-02-29', '2100-02-29', '2026-04-31', '2026-13-01', '2026-00-10', '2026-01-00', '2026-01-32', '26-01-01', '2026/01/01', '2026-1-1', '', 'abc', ' 2026-01-01', '2026-01-01 '])(
    '%j não existe',
    (text) => {
      expect(parseISODate(text)).toBeNull()
    },
  )

  it('devolve ano, mês e dia como números', () => {
    expect(parseISODate('2026-11-03')).toEqual({ year: 2026, month: 11, day: 3 })
  })
})

describe('validateDateText', () => {
  it('vazio ou só espaços pede a data', () => {
    expect(validateDateText('')).toBe(DATE_MESSAGES.empty)
    expect(validateDateText('   ')).toBe(DATE_MESSAGES.empty)
  })

  it('uma data que não existe é inválida', () => {
    for (const text of ['2026-02-30', '2026-13-01', 'ontem', '03/11/2026']) expect(validateDateText(text), text).toBe(DATE_MESSAGES.invalid)
  })

  it('o ano fica entre 2000 e 2100, inclusive', () => {
    expect(validateDateText('1999-12-31')).toBe(DATE_MESSAGES.year)
    expect(validateDateText('2000-01-01')).toBeUndefined()
    expect(validateDateText('2100-12-31')).toBeUndefined()
    expect(validateDateText('2101-01-01')).toBe(DATE_MESSAGES.year)
    expect(validateDateText('0001-01-01')).toBe(DATE_MESSAGES.year)
  })

  it('uma data futura vale (é como se registra o que vai acontecer)', () => {
    expect(validateDateText('2099-06-15')).toBeUndefined()
  })

  it('29 de fevereiro só em ano bissexto', () => {
    expect(validateDateText('2024-02-29')).toBeUndefined()
    expect(validateDateText('2025-02-29')).toBe(DATE_MESSAGES.invalid)
  })
})

describe('previousDay', () => {
  it('atravessa fim de mês, de ano e de fevereiro bissexto', () => {
    expect(previousDay('2026-11-03')).toBe('2026-11-02')
    expect(previousDay('2026-03-01')).toBe('2026-02-28')
    expect(previousDay('2024-03-01')).toBe('2024-02-29')
    expect(previousDay('2026-01-01')).toBe('2025-12-31')
  })

  it('um texto que não é data volta como veio', () => {
    expect(previousDay('abc')).toBe('abc')
  })
})

describe('formatDay: o título do grupo', () => {
  const today = '2026-11-11'

  it('Hoje e Ontem', () => {
    expect(formatDay('2026-11-11', today)).toBe('Hoje')
    expect(formatDay('2026-11-10', today)).toBe('Ontem')
  })

  it('"Ontem" vale na virada do mês e do ano', () => {
    expect(formatDay('2026-02-28', '2026-03-01')).toBe('Ontem')
    expect(formatDay('2025-12-31', '2026-01-01')).toBe('Ontem')
  })

  it('as outras datas em português, com o mês abreviado e o ano', () => {
    expect(formatDay('2026-11-03', today)).toBe('3 de nov. de 2026')
    expect(formatDay('2026-01-31', today)).toBe('31 de jan. de 2026')
    expect(formatDay('2025-05-09', today)).toBe('9 de mai. de 2025')
  })

  it('o dia de amanhã (data futura) não vira "Hoje"', () => {
    expect(formatDay('2026-11-12', today)).toBe('12 de nov. de 2026')
  })

  it('os doze meses', () => {
    const labels = Array.from({ length: 12 }, (_, i) => formatDay(toISO(2020, i + 1, 15), '2026-01-01').split(' ')[2])

    expect(labels).toEqual(['jan.', 'fev.', 'mar.', 'abr.', 'mai.', 'jun.', 'jul.', 'ago.', 'set.', 'out.', 'nov.', 'dez.'])
  })

  it('um texto que não é data volta como veio, sem quebrar', () => {
    expect(formatDay('sem data', today)).toBe('sem data')
  })

  it('sem o segundo argumento usa o dia de hoje do aparelho', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date(2026, 4, 20, 22, 30))

    expect(formatDay('2026-05-20')).toBe('Hoje')
    expect(formatDay('2026-05-19')).toBe('Ontem')
  })
})

describe('formatDate: dia/mês/ano', () => {
  it('com zeros à esquerda', () => {
    expect(formatDate('2026-11-03')).toBe('03/11/2026')
    expect(formatDate('2000-01-01')).toBe('01/01/2000')
    expect(formatDate('2100-12-31')).toBe('31/12/2100')
  })

  it('um texto que não é data volta como veio', () => {
    expect(formatDate('abc')).toBe('abc')
    expect(formatDate('')).toBe('')
  })
})
