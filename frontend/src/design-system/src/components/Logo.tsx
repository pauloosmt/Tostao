import { cx } from './utils';

export interface LogoProps {
  variant?: 'mark' | 'lockup';
  /** Altura do símbolo em px. Mínimo recomendado: 20. */
  size?: number;
  /** Slogan "CONTROLE SUAS FINANÇAS" abaixo do nome. Use só com size >= 40. */
  tagline?: boolean;
  className?: string;
}

export function Logo({ variant = 'mark', size = 32, tagline = false, className }: LogoProps) {
  const lockup = variant === 'lockup';
  const mark = (
    <svg
      className="tx-logo__mark"
      width={Math.round(size * 1.2)}
      height={size}
      viewBox="0 8 96 80"
      role={lockup ? undefined : 'img'}
      aria-label={lockup ? undefined : 'Tostão'}
      aria-hidden={lockup ? true : undefined}
    >
      <rect x={12} y={12} width={64} height={34} rx={11} className="tx-logo__flap" transform="rotate(-7 48 30)" />
      <rect x={6} y={26} width={84} height={58} rx={15} className="tx-logo__body" />
      <rect x={20} y={58} width={8} height={12} rx={4} className="tx-logo__bar" />
      <rect x={33} y={48} width={8} height={22} rx={4} className="tx-logo__bar" />
      <rect x={46} y={38} width={8} height={32} rx={4} className="tx-logo__bar" />
      <rect x={60} y={45} width={34} height={22} rx={11} className="tx-logo__tab" />
      <circle cx={73} cy={56} r={4.5} className="tx-logo__dot" />
    </svg>
  );
  if (!lockup) return mark;
  return (
    <span className={cx('tx-logo', className)} style={{ gap: Math.round(size * 0.3) }}>
      {mark}
      <span className="tx-logo__text">
        <span className="tx-logo__word" style={{ fontSize: Math.round(size * 1.05) }}>Tostão</span>
        {tagline ? (
          <span className="tx-logo__tag" style={{ fontSize: Math.max(8, Math.round(size * 0.24)) }}>CONTROLE SUAS FINANÇAS</span>
        ) : null}
      </span>
    </span>
  );
}
