import type { ReactNode } from 'react';
import { IconButton } from './IconButton';
import { cx } from './utils';

export interface SheetProps {
  title: string;
  onClose?: () => void;
  /** O botão principal, de largura total. */
  footer?: ReactNode;
  /** Use "tx-sheet--bottom" no celular. */
  className?: string;
  children: ReactNode;
}

/** Só o painel. O posicionamento (fixo à direita ou embaixo), o fundo escurecido, o Esc e o foco ficam com quem usa. */
export function Sheet({ title, onClose, footer, className, children }: SheetProps) {
  return (
    <aside className={cx('tx-sheet', className)} role="dialog" aria-label={title}>
      <header className="tx-sheet__head">
        <h2 className="heading-2">{title}</h2>
        <IconButton icon="x" label="Fechar" onClick={onClose} />
      </header>
      <div className="tx-sheet__body">{children}</div>
      {footer ? <footer className="tx-sheet__foot">{footer}</footer> : null}
    </aside>
  );
}
