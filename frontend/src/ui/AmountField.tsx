import { useId, useState } from 'react'
import { AmountInput, Icon, type AmountInputProps } from '../design-system/src'
import { MAX_CENTS, formatReaisInput, parseReais } from '../money/reais'

export interface AmountFieldProps extends Omit<AmountInputProps, 'value' | 'defaultValue' | 'onChange' | 'hint'> {
  /** O texto como a pessoa digitou (os reais, não os centavos). */
  value: string
  onChange: (text: string) => void
  /** O erro da tela (envio ou API); tem prioridade sobre o do próprio campo. */
  error?: string
}

/**
 * Texto novo do campo no estilo dos bancos: só os dígitos contam e eles entram pela direita, em centavos (`1` → `0,01`, depois `12` →
 * `0,12`, `125` → `1,25`). Letras e símbolos não mudam nada; apagar tira o último dígito. `null` = descartar a digitação (passou do limite).
 */
export function nextAmountText(typed: string, previous = ''): string | null {
  const all = typed.replace(/\D/g, '')
  if (all === '') return ''
  const digits = all.replace(/^0+/, '')
  if (digits.length > 13 || Number(digits || 0) > MAX_CENTS) return null
  // apagar até zerar volta ao campo vazio; digitar "0" num campo vazio mostra 0,00 (um limite de zero é válido)
  if (digits === '' && typed.length < previous.length) return ''
  return formatReaisInput(Number(digits || 0))
}

/**
 * O valor em destaque (`AmountInput`): a digitação segue `nextAmountText` (centavos entrando pela direita, letras ignoradas). O texto
 * guardado continua sendo os reais (`12,50`) para o resto da tela. Vazio não é erro aqui.
 */
export function AmountField({ value, onChange, error, id, ...rest }: AmountFieldProps) {
  const auto = useId()
  const inputId = id ?? auto
  const messageId = `${inputId}-msg`
  const [left, setLeft] = useState(false)

  const parsed = value.trim() === '' ? null : parseReais(value)
  const ownError = left && parsed !== null && 'error' in parsed ? parsed.error : undefined
  const message = error ?? ownError

  return (
    <div>
      <AmountInput
        {...rest}
        id={inputId}
        value={value}
        inputMode="numeric"
        onChange={(event) => {
          const next = nextAmountText(event.target.value, value)
          if (next !== null && next !== value) onChange(next)
        }}
        onBlur={() => {
          setLeft(true)
          if (parsed !== null && 'cents' in parsed) onChange(formatReaisInput(parsed.cents))
        }}
        aria-invalid={message ? true : undefined}
        aria-describedby={message ? messageId : undefined}
      />
      {message ? (
        <p id={messageId} className="tx-field__msg tx-field__msg--error text-body-sm">
          <Icon name="alert" size={16} />
          {message}
        </p>
      ) : null}
    </div>
  )
}
