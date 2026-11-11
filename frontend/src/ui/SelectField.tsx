import { useId, type SelectHTMLAttributes } from 'react'
import { Icon } from '../design-system/src'

export interface SelectOption {
  value: string
  label: string
}

export interface SelectFieldProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'className' | 'children'> {
  label: string
  options: readonly SelectOption[]
  /** Texto da opção vazia no começo ("Escolha…"); sem ele, não há opção vazia. */
  placeholder?: string
  hint?: string
  /** Substitui a dica e marca o campo como inválido. */
  error?: string
}

/**
 * Um `<select>` nativo (abre o seletor do celular, é acessível e não pede biblioteca) com o mesmo rótulo, dica e erro do `Field` do
 * design system: usa as mesmas classes, então muda com o tema e tem o mesmo foco.
 */
export function SelectField({ label, options, placeholder, hint, error, id, disabled, ...rest }: SelectFieldProps) {
  const auto = useId()
  const selectId = id ?? auto
  const messageId = `${selectId}-msg`
  const message = error ?? hint
  const invalid = Boolean(error)
  return (
    <div className={`tx-field${invalid ? ' tx-field--invalid' : ''}${disabled ? ' tx-field--disabled' : ''}`}>
      <label className="tx-field__label text-label" htmlFor={selectId}>
        {label}
      </label>
      <div className="tx-field__control">
        <select
          id={selectId}
          disabled={disabled}
          aria-invalid={invalid || undefined}
          aria-describedby={message ? messageId : undefined}
          className="tx-field__input text-body"
          {...rest}
        >
          {placeholder !== undefined ? <option value="">{placeholder}</option> : null}
          {options.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </div>
      {message ? (
        <p id={messageId} className={`tx-field__msg text-body-sm${invalid ? ' tx-field__msg--error' : ''}`}>
          {invalid ? <Icon name="alert" size={16} /> : null}
          {message}
        </p>
      ) : null}
    </div>
  )
}
