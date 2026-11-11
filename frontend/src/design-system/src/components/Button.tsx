import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { Icon, type IconName } from './Icon';
import { cx } from './utils';

export interface ButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'className'> {
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
  size?: 'sm' | 'md' | 'lg';
  icon?: IconName;
  full?: boolean;
  className?: string;
  children: ReactNode;
}

export function Button({ variant = 'primary', size = 'md', icon, full, className, type = 'button', children, ...rest }: ButtonProps) {
  return (
    <button type={type} className={cx('tx-btn', 'text-label', `tx-btn--${variant}`, `tx-btn--${size}`, full && 'tx-btn--full', className)} {...rest}>
      {icon ? <Icon name={icon} size={18} /> : null}
      {children}
    </button>
  );
}
