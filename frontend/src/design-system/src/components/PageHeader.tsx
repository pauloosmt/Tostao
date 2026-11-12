import type { ReactNode } from 'react';
import { IconButton } from './IconButton';
import { cx } from './utils';

export interface PageHeaderProps {
  title: string;
  /** Linha acima do título, como a data no Início. */
  eyebrow?: string;
  /** Linha abaixo do título, como "6 lançamentos em novembro". */
  context?: ReactNode;
  /** Ação da tela: um Button ou o MonthSwitcher. */
  action?: ReactNode;
  className?: string;
}

export function PageHeader({ title, eyebrow, context, action, className }: PageHeaderProps) {
  return (
    <header className={cx('tx-ph', className)}>
      <div>
        {eyebrow ? <p className="tx-ph__eyebrow text-body-sm">{eyebrow}</p> : null}
        <h1 className="tx-ph__title heading-1">{title}</h1>
        {context ? <p className="tx-ph__context text-body-sm">{context}</p> : null}
      </div>
      {action ? <div className="tx-ph__action">{action}</div> : null}
    </header>
  );
}

export interface MonthSwitcherProps {
  /** Mês por extenso: "Novembro de 2026". */
  label: string;
  onPrev?: () => void;
  onNext?: () => void;
  className?: string;
}

export function MonthSwitcher({ label, onPrev, onNext, className }: MonthSwitcherProps) {
  return (
    <div className={cx('tx-month', className)}>
      <IconButton icon="chevronLeft" label="Mês anterior" onClick={onPrev} />
      <span className="tx-month__label text-label">{label}</span>
      <IconButton icon="chevronRight" label="Próximo mês" onClick={onNext} />
    </div>
  );
}
