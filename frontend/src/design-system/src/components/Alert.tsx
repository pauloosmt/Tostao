import type { ReactNode } from 'react';
import { Icon } from './Icon';
import { cx } from './utils';

export interface AlertProps {
  tone?: 'info' | 'warning' | 'danger';
  title?: string;
  className?: string;
  children: ReactNode;
}

export function Alert({ tone = 'info', title, className, children }: AlertProps) {
  return (
    <div className={cx('tx-alert', `tx-alert--${tone}`, className)} role={tone === 'danger' ? 'alert' : 'status'}>
      <Icon name={tone === 'info' ? 'info' : 'alert'} size={20} className="tx-alert__icon" />
      <div>
        {title ? <p className="tx-alert__title text-label">{title}</p> : null}
        <div className="tx-alert__body text-body-sm">{children}</div>
      </div>
    </div>
  );
}
