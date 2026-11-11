import { Icon } from './Icon';
import { cx } from './utils';

export interface ProgressBarProps {
  label: string;
  value: number;
  max?: number;
  /** Texto à direita, ex.: "R$ 410,00 de R$ 800,00". */
  valueLabel?: string;
  /** Sem `tone`: brand abaixo de 80%, warning de 80% a 99%, danger a partir de 100%, sempre com texto de status. */
  tone?: 'brand' | 'warning' | 'danger';
  className?: string;
}

export function ProgressBar({ label, value, max = 100, valueLabel, tone, className }: ProgressBarProps) {
  const pct = Math.max(0, (value / max) * 100);
  const t = tone ?? (pct >= 100 ? 'danger' : pct >= 80 ? 'warning' : 'brand');
  const status = !tone && pct >= 100 ? 'Limite atingido' : !tone && pct >= 80 ? 'Perto do limite' : null;
  return (
    <div className={cx('tx-progress', `tx-progress--${t}`, className)}>
      <div className="tx-progress__head text-body-sm">
        <span className="tx-progress__label text-label">{label}</span>
        {valueLabel ? <span className="tx-progress__value tx-num">{valueLabel}</span> : null}
      </div>
      <div className="tx-progress__track" role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={max} aria-valuenow={Math.min(value, max)}>
        <div className="tx-progress__fill" style={{ width: `${Math.min(pct, 100)}%` }} />
      </div>
      {status ? (
        <p className="tx-progress__status text-body-sm">
          <Icon name="alert" size={16} />
          {status}
        </p>
      ) : null}
    </div>
  );
}
