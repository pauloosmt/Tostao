import type { MouseEvent } from 'react';
import { Icon, type IconName } from './Icon';
import { Logo } from './Logo';
import { cx } from './utils';

export interface NavItem {
  id: string;
  label: string;
  icon: IconName;
  href?: string;
}

export interface NavProps {
  items: NavItem[];
  active: string;
  /** `bottom`: barra flutuante no celular (< 720px). `side`: menu lateral no desktop (>= 960px). */
  layout?: 'bottom' | 'side';
  /** Para roteamento no cliente. Sem ele, o clique segue o `href` normalmente. */
  onSelect?: (id: string) => void;
  className?: string;
}

export function Nav({ items, active, layout = 'bottom', onSelect, className }: NavProps) {
  return (
    <nav className={cx('tx-nav', `tx-nav--${layout}`, className)} aria-label="Principal">
      {layout === 'side' ? (
        <div className="tx-nav__brand"><Logo variant="lockup" size={28} /></div>
      ) : null}
      <ul className="tx-nav__list">
        {items.map((it) => {
          const on = it.id === active;
          const click = onSelect
            ? (e: MouseEvent) => {
                e.preventDefault();
                onSelect(it.id);
              }
            : undefined;
          return (
            <li key={it.id}>
              <a href={it.href ?? '#'} className={cx('tx-nav__item', 'text-label', on && 'is-active')} aria-current={on ? 'page' : undefined} onClick={click}>
                <Icon name={it.icon} size={22} />
                <span>{it.label}</span>
              </a>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
