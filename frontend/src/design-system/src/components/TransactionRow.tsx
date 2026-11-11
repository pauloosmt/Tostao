import { Badge } from './Badge';
import { Icon, type IconName } from './Icon';
import { Money } from './Money';
import { cx } from './utils';

const ICON: Record<'income' | 'expense' | 'transfer', IconName> = { income: 'arrowDown', expense: 'arrowUp', transfer: 'swap' };

export interface TransactionRowProps {
  description: string;
  cents: number;
  kind?: 'income' | 'expense' | 'transfer';
  account?: string;
  category?: string;
  /** Já formatada, ex.: "3 nov". */
  date?: string;
  pending?: boolean;
  onClick?: () => void;
  className?: string;
}

export function TransactionRow({ description, cents, kind = 'expense', account, category, date, pending, onClick, className }: TransactionRowProps) {
  const meta = [account, category, date].filter(Boolean).join(' \u00b7 ');
  const content = (
    <>
      <span className="tx-row__icon"><Icon name={ICON[kind]} size={18} /></span>
      <span className="tx-row__main">
        <span className="tx-row__title text-body">{description}</span>
        <span className="tx-row__meta text-body-sm">
          <span className="tx-row__metatext">{meta}</span>
          {pending ? <Badge tone="warning">Pendente</Badge> : null}
        </span>
      </span>
      <Money cents={cents} kind={kind} size="md" />
    </>
  );
  // Com ação, a linha é um botão de verdade: alcançável por Tab e acionada por Enter e Espaço, com nome para leitor de tela.
  if (onClick) {
    return (
      <button type="button" className={cx('tx-row', className)} onClick={onClick}>
        {content}
      </button>
    );
  }
  return <div className={cx('tx-row', className)}>{content}</div>;
}
