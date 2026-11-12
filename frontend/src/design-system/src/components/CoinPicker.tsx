import { CategoryCoin } from './CategoryCoin';
import { cx } from './utils';

export interface CoinOption {
  name: string;
  color: string;
}

export interface CoinPickerProps {
  /** Descrição para leitores de tela. */
  label: string;
  value?: string;
  options: CoinOption[];
  onChange?: (name: string) => void;
  className?: string;
}

export function CoinPicker({ label, value, options, onChange, className }: CoinPickerProps) {
  return (
    <div className={cx('tx-coinpick', className)} role="radiogroup" aria-label={label}>
      {options.map((o) => {
        const on = o.name === value;
        return (
          <button key={o.name} type="button" role="radio" aria-checked={on} className={cx('tx-coinpick__opt', on && 'is-on')} onClick={() => onChange?.(o.name)}>
            <span className="tx-coinpick__coin"><CategoryCoin name={o.name} color={o.color} size={44} /></span>
            <span className="tx-coinpick__name text-caption">{o.name}</span>
          </button>
        );
      })}
    </div>
  );
}
