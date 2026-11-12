import { cx } from './utils';

export interface PillBarItem {
  label: string;
  value: number;
  /** Valor já formatado, escrito em cima da barra. */
  valueLabel: string;
  /** Período em curso: fica em `brand`. */
  current?: boolean;
}

export interface PillBarsProps {
  /** Descrição para leitores de tela. */
  label: string;
  items: PillBarItem[];
  height?: number;
  className?: string;
}

/** Barras-pílula verticais, de 3 a 7 barras. Para categorias, use ProgressBar com tone="brand". */
export function PillBars({ label, items, height = 120, className }: PillBarsProps) {
  const max = Math.max(1, ...items.map((i) => i.value));
  return (
    <div className={cx('tx-pills', className)} role="list" aria-label={label}>
      {items.map((it, i) => (
        <div key={i} className={cx('tx-pills__col', it.current && 'is-current')} role="listitem">
          <span className="tx-pills__value tx-num text-caption">{it.valueLabel}</span>
          <span className="tx-pills__bar" style={{ height: Math.max(22, Math.round((it.value / max) * height)) }} />
          <span className="tx-pills__label text-caption">{it.label}</span>
        </div>
      ))}
    </div>
  );
}
