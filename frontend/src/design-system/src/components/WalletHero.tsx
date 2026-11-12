import type { ReactNode } from 'react';
import { Icon } from './Icon';
import { Money } from './Money';
import { cx } from './utils';

export interface WalletHeroProps {
  label: string;
  /** Saldo em centavos. Nulo mostra `empty` no lugar. */
  cents?: number | null;
  sub?: string;
  /** Frase do fecho dourado: "Sobram R$ 1.723,60 em novembro". */
  clasp?: string;
  empty?: string;
  /** Carteira fechada: esconde o saldo e a frase do fecho. */
  closed?: boolean;
  /** Com ele, aparece o botão "Fechar carteira" / "Abrir carteira". */
  onToggle?: () => void;
  /** Use "tx-wallet--stack" no celular. */
  className?: string;
  children?: ReactNode;
}

/** A Carteira: existe uma vez só, no topo do Início. */
export function WalletHero({ label, cents, sub, clasp, empty, closed = false, onToggle, className, children }: WalletHeroProps) {
  return (
    <section className={cx('tx-wallet', clasp && 'tx-wallet--clasp', closed && 'is-closed', className)}>
      <div className="tx-wallet__flap" aria-hidden="true" />
      <div className="tx-wallet__body">
        <div className="tx-wallet__content" aria-hidden={closed || undefined}>
          <p className="tx-wallet__label text-label">{label}</p>
          {cents == null ? <p className="tx-wallet__empty title">{empty}</p> : <Money cents={cents} size="xl" className="tx-wallet__amount" />}
          {sub ? <p className="tx-wallet__sub text-body-sm">{sub}</p> : null}
          {children}
        </div>
        <div className="tx-wallet__cover" aria-hidden={!closed || undefined}>
          <p className="text-label">Carteira fechada</p>
          <p className="tx-wallet__dots" aria-hidden="true">R$ <span>••••</span></p>
          <p className="text-body-sm">Saldo oculto</p>
        </div>
        {onToggle ? (
          <button type="button" className="tx-wallet__toggle text-label" aria-pressed={closed} onClick={onToggle}>
            <Icon name={closed ? 'unlock' : 'lock'} size={18} />
            {closed ? 'Abrir carteira' : 'Fechar carteira'}
          </button>
        ) : null}
      </div>
      {clasp ? (
        <div className="tx-wallet__clasp text-label">
          <i aria-hidden="true" />
          <span className="tx-num">{closed ? 'Carteira fechada' : clasp}</span>
        </div>
      ) : null}
    </section>
  );
}
