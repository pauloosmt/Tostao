import type { ButtonHTMLAttributes } from 'react';
import { Icon, type IconName } from './Icon';
import { cx } from './utils';

export interface IconButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'className' | 'children'> {
  icon: IconName;
  /** Obrigatório: é o nome lido por leitores de tela. */
  label: string;
  size?: number;
  className?: string;
}

export function IconButton({ icon, label, size = 20, className, type = 'button', ...rest }: IconButtonProps) {
  return (
    <button type={type} className={cx('tx-iconbtn', className)} aria-label={label} {...rest}>
      <Icon name={icon} size={size} />
    </button>
  );
}
