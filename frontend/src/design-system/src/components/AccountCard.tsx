import { Icon, type IconName } from './Icon';
import { Money } from './Money';
import { cx } from './utils';

export type AccountType = 'corrente' | 'poupanca' | 'dinheiro' | 'cartao' | 'investimento';

const TYPES: Record<AccountType, { label: string; icon: IconName }> = {
  corrente: { label: 'Conta corrente', icon: 'bank' },
  poupanca: { label: 'Poupança', icon: 'coin' },
  dinheiro: { label: 'Dinheiro', icon: 'cash' },
  cartao: { label: 'Cartão de crédito', icon: 'card' },
  investimento: { label: 'Investimento', icon: 'trend' },
};

const UNKNOWN: { label: string; icon: IconName } = { label: 'Conta', icon: 'bank' };

export interface AccountCardProps {
  name: string;
  type: AccountType;
  balanceCents: number;
  /** Linha de apoio sob o saldo, ex.: "Fecha dia 10". */
  note?: string;
  archived?: boolean;
  className?: string;
}

export function AccountCard({ name, type, balanceCents, note, archived, className }: AccountCardProps) {
  // Um tipo que o app ainda não conhece (outra versão da API) aparece como "Conta", nunca como um tipo que ele não é.
  const t = TYPES[type] ?? UNKNOWN;
  return (
    <div className={cx('tx-account', archived && 'is-archived', className)}>
      <span className="tx-account__icon"><Icon name={t.icon} size={22} /></span>
      <div className="tx-account__main">
        <p className="tx-account__name title">{name}</p>
        <p className="tx-account__type text-body-sm">{t.label}{archived ? ' \u00b7 arquivada' : ''}</p>
      </div>
      <div className="tx-account__bal">
        <Money cents={balanceCents} kind="balance" size="lg" />
        {note ? <p className="tx-account__note text-body-sm">{note}</p> : null}
      </div>
    </div>
  );
}
