import { useState } from 'react'
import { Field, type FieldProps } from '../design-system/src'
import { formatReaisInput, parseReais } from '../money/reais'

export interface MoneyFieldProps extends Omit<FieldProps, 'prefix' | 'type' | 'inputMode' | 'value' | 'onChange' | 'numeric'> {
  /** O texto como a pessoa digitou (os reais, não os centavos). */
  value: string
  onChange: (text: string) => void
}

/**
 * Campo de valor em reais ("R$ 1.234,56"). Guarda o texto digitado; ao sair do campo, um valor válido é reescrito no formato do
 * aplicativo (`50` vira `50,00`) e um valor inválido mostra a mensagem do `parseReais` no campo. O erro que a tela passa em `error`
 * (a validação do envio, ou da API) tem prioridade. Vazio não é erro aqui: se o valor é obrigatório, quem valida o envio diz.
 */
export function MoneyField({ value, onChange, error, ...field }: MoneyFieldProps) {
  const [left, setLeft] = useState(false)

  const parsed = value.trim() === '' ? null : parseReais(value)
  const ownError = left && parsed !== null && 'error' in parsed ? parsed.error : undefined

  return (
    <Field
      {...field}
      type="text"
      inputMode="decimal"
      autoComplete="off"
      prefix="R$"
      numeric
      value={value}
      onChange={(event) => onChange(event.target.value)}
      onBlur={() => {
        setLeft(true)
        if (parsed !== null && 'cents' in parsed) onChange(formatReaisInput(parsed.cents))
      }}
      error={error ?? ownError}
    />
  )
}
