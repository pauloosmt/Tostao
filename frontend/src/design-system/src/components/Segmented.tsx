import { cx } from './utils';

export interface SegmentedProps<T extends string = string> {
  /** Descrição para leitores de tela, ex.: "Tipo do lançamento". */
  label: string;
  value: T;
  options: { value: T; label: string }[];
  onChange?: (value: T) => void;
  className?: string;
}

export function Segmented<T extends string = string>({ label, value, options, onChange, className }: SegmentedProps<T>) {
  return (
    <div className={cx('tx-seg', className)} role="radiogroup" aria-label={label}>
      {options.map((o) => {
        const on = o.value === value;
        return (
          <button
            key={o.value}
            type="button"
            role="radio"
            aria-checked={on}
            className={cx('tx-seg__opt', 'text-label', on && 'is-on')}
            onClick={() => onChange?.(o.value)}
          >
            {o.label}
          </button>
        );
      })}
    </div>
  );
}
