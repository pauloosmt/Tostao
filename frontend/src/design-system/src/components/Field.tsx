import { useId, type InputHTMLAttributes } from 'react';
import { Icon } from './Icon';
import { cx } from './utils';

export interface FieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'className' | 'prefix'> {
  label: string;
  /** Texto fixo antes do valor, ex.: "R$". */
  prefix?: string;
  hint?: string;
  /** Substitui a dica e marca o campo como inválido. */
  error?: string;
  /** Algarismos tabulares (valores e datas). */
  numeric?: boolean;
  className?: string;
}

export function Field({ label, prefix, hint, error, numeric, className, id, disabled, ...rest }: FieldProps) {
  const auto = useId();
  const inputId = id ?? auto;
  const msgId = `${inputId}-msg`;
  const invalid = Boolean(error);
  const msg = error ?? hint;
  return (
    <div className={cx('tx-field', invalid && 'tx-field--invalid', disabled && 'tx-field--disabled', className)}>
      <label className="tx-field__label text-label" htmlFor={inputId}>{label}</label>
      <div className="tx-field__control">
        {prefix ? <span className="tx-field__prefix text-body" aria-hidden="true">{prefix}</span> : null}
        <input
          id={inputId}
          disabled={disabled}
          aria-invalid={invalid || undefined}
          aria-describedby={msg ? msgId : undefined}
          className={cx('tx-field__input', 'text-body', numeric && 'tx-num')}
          {...rest}
        />
      </div>
      {msg ? (
        <p id={msgId} className={cx('tx-field__msg', 'text-body-sm', invalid && 'tx-field__msg--error')}>
          {invalid ? <Icon name="alert" size={16} /> : null}
          {msg}
        </p>
      ) : null}
    </div>
  );
}
