import type { MouseEvent, ReactNode } from 'react';
import { Avatar } from './Avatar';
import { Button } from './Button';
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
  /** Mostra o botão de lançar: no topo do menu lateral (com a dica da tecla N) ou no centro da barra. */
  launchLabel?: string;
  onLaunch?: () => void;
  /** Rodapé do menu lateral: leva ao Perfil. */
  user?: { name: string; sub?: string; href?: string };
  onUser?: () => void;
  className?: string;
}

export function Nav({ items, active, layout = 'bottom', onSelect, launchLabel, onLaunch, user, onUser, className }: NavProps) {
  const list: ReactNode[] = items.map((it) => {
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
  });
  if (layout === 'bottom' && launchLabel) {
    list.splice(
      Math.ceil(items.length / 2),
      0,
      <li key="__launch" className="tx-nav__launchslot">
        <button type="button" className="tx-nav__launch" aria-label={launchLabel} onClick={onLaunch}>
          <Icon name="plus" size={26} />
        </button>
      </li>,
    );
  }
  return (
    <nav className={cx('tx-nav', `tx-nav--${layout}`, className)} aria-label="Principal">
      {layout === 'side' ? <div className="tx-nav__brand"><Logo variant="lockup" size={28} /></div> : null}
      {layout === 'side' && launchLabel ? (
        <Button icon="plus" full onClick={onLaunch}>
          {launchLabel}
          <kbd className="tx-kbd">N</kbd>
        </Button>
      ) : null}
      <ul className="tx-nav__list">{list}</ul>
      {layout === 'side' && user ? (
        <a
          href={user.href ?? '#'}
          className="tx-nav__user"
          onClick={onUser ? (e) => { e.preventDefault(); onUser(); } : undefined}
        >
          <Avatar name={user.name} size={36} />
          <span className="tx-nav__usertext">
            <span className="text-label">{user.name}</span>
            {user.sub ? <span className="text-caption">{user.sub}</span> : null}
          </span>
        </a>
      ) : null}
    </nav>
  );
}
