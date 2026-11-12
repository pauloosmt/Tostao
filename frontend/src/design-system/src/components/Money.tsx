import { cx, formatBRL } from './utils';

export type MoneyKind = 'balance' | 'income' | 'expense' | 'transfer';
export type MoneySize = 'sm' | 'md' | 'lg' | 'xl';

const WORDS: Record<MoneyKind, string> = { balance: 'Saldo', income: 'Receita', expense: 'Despesa', transfer: 'Transferência' };
const AMOUNT: Record<MoneySize, string> = { sm: 'text-body-sm', md: 'amount-md', lg: 'amount-lg', xl: 'amount-xl' };

export interface MoneyProps {
  /** Valor em centavos inteiros. O sinal exibido vem de `kind`, não do número. */
  cents: number;
  kind?: MoneyKind;
  size?: MoneySize;
  className?: string;
}

export function Money({ cents, kind = 'balance', size = 'md', className }: MoneyProps) {
  const sign = kind === 'balance' ? (cents < 0 ? '\u2212' : '') : kind === 'income' ? '+' : kind === 'expense' ? '\u2212' : '';
  const str = formatBRL(cents);
  const cut = str.lastIndexOf(',');
  const big = size === 'lg' || size === 'xl';
  return (
    <span className={cx('tx-num', 'tx-money', AMOUNT[size], kind === 'income' && 'tx-money--income', kind === 'transfer' && 'tx-money--transfer', className)}>
      <span className="tx-sr">{WORDS[kind] + (kind === 'balance' && cents < 0 ? ' negativo ' : ' ')}</span>
      {big ? sign + str.slice(0, cut) : sign + str}
      {big ? <span className="tx-money__cents">{str.slice(cut)}</span> : null}
    </span>
  );
}

export interface StatTileProps {
  label: string;
  cents: number;
  kind?: MoneyKind;
  size?: MoneySize;
  hint?: string;
  className?: string;
}

export function StatTile({ label, cents, kind = 'balance', size = 'lg', hint, className }: StatTileProps) {
  return (
    <div className={cx('tx-stat', className)}>
      <p className="tx-stat__label text-label">{label}</p>
      <Money cents={cents} kind={kind} size={size} />
      {hint ? <p className="tx-stat__hint text-body-sm">{hint}</p> : null}
    </div>
  );
}
