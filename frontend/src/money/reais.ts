// Dinheiro digitado em reais -> centavos inteiros, com exatidão. O texto é lido como TEXTO (nunca `parseFloat`, que erra em
// centavos): só aceita o que é claramente um valor em reais e, no resto, diz como escrever. Sem arredondar em silêncio.

/** O limite da API: |valor| de até R$ 10.000.000.000,00, em centavos. */
export const MAX_CENTS = 1_000_000_000_000

export const REAIS_MESSAGES = {
  format: 'Informe um valor em reais, como 1.234,56.',
  comma: 'Use vírgula para os centavos, por exemplo 12,50.',
  decimals: 'Use no máximo 2 casas decimais.',
  max: 'O valor máximo é R$ 10.000.000.000,00.',
  min: 'O valor mínimo é −R$ 10.000.000.000,00.',
} as const

export type ReaisResult = { cents: number } | { error: string }

const MINUS = /^[-−]/
// parte inteira com ou sem ponto de milhar bem formado (grupos de 3), e a parte decimal depois da vírgula
const WELL_FORMED = /^(\d{1,3}(?:\.\d{3})+|\d+)(?:,(\d+))?$/

/** `"1.234,56"`, `"R$ 12,5"`, `"-50"` ... em centavos (`123456`, `1250`, `-5000`); ou `{ error }` com a mensagem para o campo. */
export function parseReais(input: string): ReaisResult {
  let text = input.replace(/ /g, ' ').trim()
  let negative = false

  // O sinal e o "R$" podem vir em qualquer ordem ("-R$ 5", "R$ -5"), mas cada um só uma vez.
  let currency = false
  for (;;) {
    if (MINUS.test(text)) {
      if (negative) return { error: REAIS_MESSAGES.format }
      negative = true
      text = text.slice(1).trimStart()
    } else if (/^R\$/i.test(text)) {
      if (currency) return { error: REAIS_MESSAGES.format }
      currency = true
      text = text.slice(2).trimStart()
    } else {
      break
    }
  }
  if (text === '' || /[^\d.,]/.test(text)) return { error: REAIS_MESSAGES.format }

  const match = WELL_FORMED.exec(text)
  if (!match) {
    // Só dígitos e pontos, mas mal agrupados ("12.5", "1.23", "12.34,5"): o erro mais comum é o ponto como decimal.
    if (/^[\d.]+(?:,\d+)?$/.test(text) && text.includes('.')) return { error: REAIS_MESSAGES.comma }
    return { error: REAIS_MESSAGES.format }
  }

  const whole = match[1].replace(/\./g, '')
  const fraction = match[2] ?? ''
  if (fraction.length > 2) return { error: REAIS_MESSAGES.decimals }
  // 13 dígitos já passam do limite; recusar antes de converter mantém a conta dentro dos inteiros exatos.
  if (whole.replace(/^0+/, '').length > 13) return { error: negative ? REAIS_MESSAGES.min : REAIS_MESSAGES.max }

  const cents = Number(whole) * 100 + Number(fraction.padEnd(2, '0'))
  if (cents > MAX_CENTS) return { error: negative ? REAIS_MESSAGES.min : REAIS_MESSAGES.max }
  return { cents: negative && cents !== 0 ? -cents : cents }
}

/** Centavos em texto de campo, sem o "R$": `123456` -> `"1.234,56"`, `-5000` -> `"-50,00"`. */
export function formatReaisInput(cents: number): string {
  const abs = Math.abs(Math.trunc(cents))
  const reais = String(Math.floor(abs / 100)).replace(/\B(?=(\d{3})+(?!\d))/g, '.')
  const rest = String(abs % 100).padStart(2, '0')
  return `${cents < 0 ? '-' : ''}${reais},${rest}`
}
