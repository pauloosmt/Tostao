import { CATEGORY_COLORS, inkOn } from './categoryIcons';
import { Icon } from './Icon';
import { cx } from './utils';

export interface SwatchPickerProps {
  label?: string;
  value?: string;
  options?: string[];
  onChange?: (color: string) => void;
  className?: string;
}

/** Cor da categoria. Subcategoria não escolhe cor: herda a da mãe. */
export function SwatchPicker({ label = 'Cor', value, options = CATEGORY_COLORS, onChange, className }: SwatchPickerProps) {
  return (
    <div className={cx('tx-swatches', className)} role="radiogroup" aria-label={label}>
      {options.map((c, i) => {
        const selected = c === value;
        return (
          <button key={c} type="button" role="radio" aria-checked={selected} aria-label={`Cor ${i + 1}`} className={cx('tx-swatch', selected && 'is-on')} onClick={() => onChange?.(c)}>
            <span style={{ background: c, color: inkOn(c) }}>{selected ? <Icon name="check" size={16} /> : null}</span>
          </button>
        );
      })}
    </div>
  );
}
