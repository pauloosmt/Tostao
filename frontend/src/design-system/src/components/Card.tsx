import type { ReactNode } from 'react';
import { cx } from './utils';

export interface CardProps {
  title?: string;
  /** Elemento à direita do título (ex.: um Button ghost). */
  action?: ReactNode;
  /** Sombra, só para elementos que flutuam. */
  raised?: boolean;
  /** Sem padding, para listas de ponta a ponta. */
  flush?: boolean;
  className?: string;
  children: ReactNode;
}

export function Card({ title, action, raised, flush, className, children }: CardProps) {
  return (
    <section className={cx('tx-card', raised && 'tx-card--raised', flush && 'tx-card--flush', className)}>
      {title || action ? (
        <header className="tx-card__head">
          {title ? <h2 className="tx-card__title heading-2">{title}</h2> : null}
          {action}
        </header>
      ) : null}
      {children}
    </section>
  );
}
