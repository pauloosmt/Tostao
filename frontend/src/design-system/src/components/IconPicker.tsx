import { useState } from 'react';
import { AttentionDot } from './AttentionDot';
import { CategoryGlyph } from './CategoryCoin';
import { CATEGORY_ICONS, CATEGORY_ICON_ORDER, inkOn, isCategoryIcon, matchesIconQuery, suggestCategoryIcons, type CategoryIconName } from './categoryIcons';
import { cx } from './utils';

export interface IconPickerProps {
  label?: string;
  /** Ícone escolhido; `false` = sem ícone, usar a inicial. */
  value?: CategoryIconName | string | false | null;
  /** Nome digitado da categoria, para as sugestões. */
  name?: string;
  /** Cor da moeda (da categoria, ou da mãe se for subcategoria). */
  color?: string;
  onChange?: (icon: CategoryIconName | false) => void;
  className?: string;
}

/**
 * Enquanto a pessoa não escolher nada, passe em `value` a primeira sugestão (`suggestCategoryIcon(name)`):
 * o ícone acompanha o nome. Depois do primeiro `onChange`, guarde a escolha dela.
 */
export function IconPicker({ label = 'Ícone', value, name = '', color = '#8c877a', onChange, className }: IconPickerProps) {
  const [query, setQuery] = useState('');
  const chosen = isCategoryIcon(value) ? value : null;
  const on = { background: color, color: inkOn(color), borderColor: 'transparent' };
  const button = (key: CategoryIconName, prefix = '') => {
    const selected = chosen === key;
    return (
      <button
        key={prefix + key}
        type="button"
        role="radio"
        aria-checked={selected}
        aria-label={CATEGORY_ICONS[key].label}
        title={CATEGORY_ICONS[key].label}
        className={cx('tx-iconpick__btn', selected && 'is-on')}
        style={selected ? on : undefined}
        onClick={() => onChange?.(key)}
      >
        <CategoryGlyph icon={key} size={20} />
      </button>
    );
  };
  const suggestions = suggestCategoryIcons(name, 3);
  const groups: { name: string; keys: CategoryIconName[] }[] = [];
  for (const key of CATEGORY_ICON_ORDER) {
    if (query && !matchesIconQuery(key, query)) continue;
    const groupName = CATEGORY_ICONS[key].group;
    let group = groups.find((g) => g.name === groupName);
    if (!group) {
      group = { name: groupName, keys: [] };
      groups.push(group);
    }
    group.keys.push(key);
  }
  return (
    <div className={cx('tx-iconpick', className)} role="radiogroup" aria-label={label}>
      <div className="tx-iconpick__top">
        {suggestions.length ? (
          <span className="tx-iconpick__sugg">
            <AttentionDot>Sugestões</AttentionDot>
            {suggestions.map((key) => button(key, 's-'))}
          </span>
        ) : null}
        <button
          type="button"
          role="radio"
          aria-checked={value === false}
          aria-label="Sem ícone, usar a inicial"
          title="Sem ícone, usar a inicial"
          className={cx('tx-iconpick__btn', 'tx-iconpick__initial', value === false && 'is-on')}
          style={value === false ? on : undefined}
          onClick={() => onChange?.(false)}
        >
          {name.trim().charAt(0).toUpperCase() || 'A'}
        </button>
      </div>
      <input className="tx-iconpick__search text-body-sm" type="search" placeholder="Buscar ícone" aria-label="Buscar ícone" value={query} onChange={(e) => setQuery(e.target.value)} />
      <div className="tx-iconpick__scroll">
        {groups.length ? (
          groups.map((g) => (
            <div key={g.name} className="tx-iconpick__group">
              <span className="text-caption">{g.name}</span>
              <div className="tx-iconpick__grid">{g.keys.map((key) => button(key))}</div>
            </div>
          ))
        ) : (
          <p className="text-body-sm tx-iconpick__none">Nenhum ícone com esse nome. A moeda usa a inicial.</p>
        )}
      </div>
    </div>
  );
}
