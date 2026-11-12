import { cx } from './utils';

function luminance(hex: string): number {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex);
  if (!m) return 0.5;
  const n = parseInt(m[1], 16);
  const [r, g, b] = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((v) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

export interface CategoryCoinProps {
  name: string;
  /** Campo `color` de `categories`, em hex. */
  color?: string;
  size?: number;
  className?: string;
}

/** Moeda de categoria: círculo na cor do dado com a inicial. Decorativa; o nome sempre aparece em texto ao lado. */
export function CategoryCoin({ name, color = '#8c877a', size = 36, className }: CategoryCoinProps) {
  return (
    <span
      className={cx('tx-coin', className)}
      aria-hidden="true"
      style={{ width: size, height: size, background: color, color: luminance(color) > 0.3 ? '#08201f' : '#ffffff', fontSize: Math.round(size * 0.42) }}
    >
      {(name || '?').trim().charAt(0).toUpperCase()}
    </span>
  );
}
