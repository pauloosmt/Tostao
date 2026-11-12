import type { ReactNode } from 'react';
import { Icon, type IconName } from './Icon';
import { cx } from './utils';

export interface OptionTileProps {
  icon?: IconName;
  selected?: boolean;
  /** Formato de chip, para escolhas rápidas ("Hoje", "Ontem"). */
  compact?: boolean;
  onClick?: () => void;
  className?: string;
  children: ReactNode;
}

/** Agrupe os blocos num elemento com role="radiogroup". */
export function OptionTile({ icon, selected, compact, onClick, className, children }: OptionTileProps) {
  return (
    <button type="button" role="radio" aria-checked={Boolean(selected)} className={cx('tx-tile', selected && 'is-on', compact && 'tx-tile--compact', className)} onClick={onClick}>
      {icon ? <Icon name={icon} size={compact ? 18 : 22} /> : null}
      <span className="text-label">{children}</span>
    </button>
  );
}
