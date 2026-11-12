import { CATEGORY_ICONS, inkOn, isCategoryIcon, suggestCategoryIcon, type CategoryIconName } from './categoryIcons';
import { cx } from './utils';

export function CategoryGlyph({ icon, size, weight = 1.9 }: { icon: CategoryIconName; size: number; weight?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={weight} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={CATEGORY_ICONS[icon].d} />
    </svg>
  );
}

export interface CategoryCoinProps {
  name: string;
  /** Campo `color` de `categories`, em hex. Subcategoria usa a cor da mãe. */
  color?: string;
  /** Campo `icon` de `categories`. Ausente: sugere pelo nome e, sem sugestão, mostra a inicial. `false` força a inicial. */
  icon?: CategoryIconName | string | false | null;
  /** `solid` para categoria, `outline` (moeda vazada) para subcategoria. */
  variant?: 'solid' | 'outline';
  size?: number;
  className?: string;
}

/** Moeda de categoria. Decorativa: o nome sempre aparece em texto ao lado. */
export function CategoryCoin({ name, color = '#8c877a', icon, variant = 'solid', size = 36, className }: CategoryCoinProps) {
  const outline = variant === 'outline';
  const key = icon === false ? null : isCategoryIcon(icon) ? icon : suggestCategoryIcon(name);
  const base = { width: size, height: size, fontSize: Math.round(size * 0.42) };
  const style = outline ? { ...base, borderColor: color } : { ...base, background: color, color: inkOn(color) };
  return (
    <span className={cx('tx-coin', outline && 'tx-coin--outline', className)} aria-hidden="true" style={style}>
      {key ? <CategoryGlyph icon={key} size={Math.round(size * (outline ? 0.56 : 0.54))} weight={size <= 28 ? 2.2 : 1.9} /> : (name || '?').trim().charAt(0).toUpperCase()}
    </span>
  );
}
