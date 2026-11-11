import { useId, type TextareaHTMLAttributes } from 'react'
import { Icon } from '../design-system/src'

export interface TextAreaFieldProps extends Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, 'className' | 'maxLength'> {
  label: string
  hint?: string
  /** Substitui a dica e marca o campo como inválido. */
  error?: string
  /** O limite de caracteres, mostrado em um contador ("12/1000"). Não corta o que se digita: quem valida é o envio. */
  max?: number
}

/**
 * Uma área de texto nativa com o mesmo rótulo, dica e erro do `Field` do design system (usa as mesmas classes, então muda com o tema e tem o
 * mesmo foco), mais um contador opcional. O contador fica ligado ao campo por `aria-describedby`, mas sem anunciar a cada tecla.
 */
export function TextAreaField({ label, hint, error, max, id, disabled, value, rows = 3, ...rest }: TextAreaFieldProps) {
  const auto = useId()
  const fieldId = id ?? auto
  const messageId = `${fieldId}-msg`
  const countId = `${fieldId}-count`
  const message = error ?? hint
  const invalid = Boolean(error)
  const length = typeof value === 'string' ? value.length : 0
  const over = max !== undefined && length > max
  const describedBy = [message ? messageId : null, max !== undefined ? countId : null].filter(Boolean).join(' ') || undefined
  return (
    <div className={`tx-field${invalid ? ' tx-field--invalid' : ''}${disabled ? ' tx-field--disabled' : ''}`}>
      <label className="tx-field__label text-label" htmlFor={fieldId}>
        {label}
      </label>
      <div className="tx-field__control tx-field__control--area">
        <textarea
          id={fieldId}
          rows={rows}
          disabled={disabled}
          value={value}
          aria-invalid={invalid || undefined}
          aria-describedby={describedBy}
          className="tx-field__input text-body"
          {...rest}
        />
      </div>
      <div className="text-field-footer">
        {message ? (
          <p id={messageId} className={`tx-field__msg text-body-sm${invalid ? ' tx-field__msg--error' : ''}`}>
            {invalid ? <Icon name="alert" size={16} /> : null}
            {message}
          </p>
        ) : (
          <span />
        )}
        {max !== undefined ? (
          <span id={countId} className={`text-caption text-field-count${over ? ' text-field-count--over' : ''}`}>
            {length}/{max}
          </span>
        ) : null}
      </div>
    </div>
  )
}
