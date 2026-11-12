import type { CSSProperties } from 'react';
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
  const index = options.findIndex((o) => o.value === value);
  const track = { '--tx-index': Math.max(index, 0), '--tx-count': options.length } as CSSProperties;
  return (
    <div className={cx('tx-seg', className)} style={track} role="radiogroup" aria-label={label}>
      {/* A pílula desliza até a opção escolhida (só decoração: o estado vai em aria-checked). */}
      <span className={cx('tx-seg__pill', index < 0 && 'is-hidden')} aria-hidden="true" />
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
