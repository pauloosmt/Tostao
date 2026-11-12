import type { ReactNode } from 'react';
import { cx } from './utils';

export interface AttentionDotProps {
  className?: string;
  /** A palavra que acompanha o ponto: "Pendente", "Perto do limite". */
  children: ReactNode;
}

/** O tostão: ponto dourado de 8px ao lado do que pede atenção. No máximo um por linha. */
export function AttentionDot({ className, children }: AttentionDotProps) {
  return (
    <span className={cx('tx-attn', className)}>
      <i className="tx-attn__dot" aria-hidden="true" />
      {children}
    </span>
  );
}
