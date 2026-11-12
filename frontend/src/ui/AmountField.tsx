import { useId, useState } from 'react'
import { AmountInput, Icon, type AmountInputProps } from '../design-system/src'
import { formatReaisInput, parseReais } from '../money/reais'

export interface AmountFieldProps extends Omit<AmountInputProps, 'value' | 'defaultValue' | 'onChange' | 'hint'> {
  /** O texto como a pessoa digitou (os reais, não os centavos). */
  value: string
  onChange: (text: string) => void
  /** O erro da tela (envio ou API); tem prioridade sobre o do próprio campo. */
  error?: string
}

/**
 * O valor em destaque (`AmountInput`) com o mesmo comportamento do `MoneyField`: guarda o texto digitado; ao sair do campo, um valor
 * válido é reescrito no formato do aplicativo (`50` vira `50,00`) e um inválido mostra a mensagem do `parseReais`. Vazio não é erro aqui.
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
        onChange={(event) => onChange(event.target.value)}
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
