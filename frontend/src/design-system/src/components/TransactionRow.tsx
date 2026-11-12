import { AttentionDot } from './AttentionDot';
import { Badge } from './Badge';
import { CategoryCoin } from './CategoryCoin';
import { Icon, type IconName } from './Icon';
import { Money } from './Money';
import { cx } from './utils';

const ICON: Record<'income' | 'expense' | 'transfer', IconName> = { income: 'arrowDown', expense: 'arrowUp', transfer: 'swap' };

export interface TransactionRowProps {
  description: string;
  cents: number;
  kind?: 'income' | 'expense' | 'transfer';
  account?: string;
  category?: string | null;
  /** Cor da categoria: troca a seta pela moeda. */
  categoryColor?: string | null;
  /** Já formatada, ex.: "3 nov". */
  date?: string;
  /** Texto ao lado do tostão dourado, ex.: "Pendente". */
  attention?: string;
  pending?: boolean;
  /** Selos extras (ex.: "Parcela 2 de 10", "Recorrente"). Fazem parte da linha: n\u00e3o s\u00e3o controles. */
  tags?: string[];
  onClick?: () => void;
  className?: string;
}

export function TransactionRow({ description, cents, kind = 'expense', account, category, categoryColor, date, attention, pending, tags, onClick, className }: TransactionRowProps) {
  const meta = [account, category, date].filter(Boolean).join(' \u00b7 ');
  const content = (
    <>
      {categoryColor ? (
        <CategoryCoin name={category || description} color={categoryColor} size={36} />
      ) : (
        <span className="tx-row__icon"><Icon name={ICON[kind]} size={18} /></span>
      )}
      <span className="tx-row__main">
        <span className="tx-row__title text-body">{description}</span>
        <span className="tx-row__meta text-body-sm">
          {attention ? <AttentionDot>{attention}</AttentionDot> : null}
          <span className="tx-row__metatext">{meta}</span>
          {pending && !attention ? <Badge tone="warning">Pendente</Badge> : null}
          {tags?.map((tag) => <Badge key={tag} tone="neutral">{tag}</Badge>)}
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
