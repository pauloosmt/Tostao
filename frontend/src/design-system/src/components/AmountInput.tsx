import { useId, type InputHTMLAttributes } from 'react';
import { cx } from './utils';

export interface AmountInputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'className' | 'size'> {
  kind?: 'income' | 'expense' | 'transfer';
  label?: string;
  hint?: string;
  className?: string;
}

/** Campo de valor em destaque. O texto é o que a pessoa digita ("50,00"); converter para centavos fica com quem usa. */
export function AmountInput({ kind = 'expense', label = 'Valor', hint, className, id, value, defaultValue, ...rest }: AmountInputProps) {
  const auto = useId();
  const inputId = id ?? auto;
  const sign = kind === 'income' ? '+' : kind === 'expense' ? '\u2212' : '';
  const text = String(value ?? defaultValue ?? '');
  return (
    <div className={cx('tx-amount', `tx-amount--${kind}`, className)}>
      <label className="tx-amount__label text-label" htmlFor={inputId}>{label}</label>
      <div className="tx-amount__row tx-num">
        <span className="tx-amount__prefix" aria-hidden="true">{sign}R$</span>
        <input
          id={inputId}
          className="tx-amount__input tx-num"
          inputMode="decimal"
          autoComplete="off"
          placeholder="0,00"
          value={value}
          defaultValue={defaultValue}
          size={Math.max(4, text.length)}
          {...rest}
        />
      </div>
      {hint ? <p className="tx-amount__hint text-body-sm">{hint}</p> : null}
    </div>
  );
}
