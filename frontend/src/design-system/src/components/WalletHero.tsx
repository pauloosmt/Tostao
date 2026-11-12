import type { ReactNode } from 'react';
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
  /** Use "tx-wallet--stack" no celular. */
  className?: string;
  children?: ReactNode;
}

/** A Carteira: existe uma vez só, no topo do Início. */
export function WalletHero({ label, cents, sub, clasp, empty, className, children }: WalletHeroProps) {
  return (
    <section className={cx('tx-wallet', clasp && 'tx-wallet--clasp', className)}>
      <div className="tx-wallet__flap" aria-hidden="true" />
      <div className="tx-wallet__body">
        <p className="tx-wallet__label text-label">{label}</p>
        {cents == null ? <p className="tx-wallet__empty title">{empty}</p> : <Money cents={cents} size="xl" className="tx-wallet__amount" />}
        {sub ? <p className="tx-wallet__sub text-body-sm">{sub}</p> : null}
        {children}
      </div>
      {clasp ? (
        <div className="tx-wallet__clasp text-label">
          <i aria-hidden="true" />
          <span className="tx-num">{clasp}</span>
        </div>
      ) : null}
    </section>
  );
}
