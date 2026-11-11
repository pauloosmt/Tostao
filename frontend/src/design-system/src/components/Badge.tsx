import type { ReactNode } from 'react';
import { cx } from './utils';

export interface BadgeProps {
  tone?: 'neutral' | 'brand' | 'warning' | 'danger';
  /** Cor do ponto de categoria (campo `color` de `categories`). */
  color?: string;
  className?: string;
  children: ReactNode;
}

export function Badge({ tone = 'neutral', color, className, children }: BadgeProps) {
  return (
    <span className={cx('tx-badge', 'text-caption', `tx-badge--${tone}`, className)}>
      {color ? <span className="tx-badge__dot" style={{ background: color }} aria-hidden="true" /> : null}
      {children}
    </span>
  );
}
