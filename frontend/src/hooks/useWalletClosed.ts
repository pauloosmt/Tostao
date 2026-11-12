import { useCallback, useState } from 'react';

const KEY = 'tostao:carteira-fechada';

/** Lembra, neste navegador, se a pessoa deixou a carteira fechada. Guarda só "1" ou nada; nenhum valor financeiro. */
export function useWalletClosed(): [boolean, () => void] {
  const [closed, setClosed] = useState(() => {
    try {
      return localStorage.getItem(KEY) === '1';
    } catch {
      return false;
    }
  });
  const toggle = useCallback(() => {
    setClosed((prev) => {
      const next = !prev;
      try {
        if (next) localStorage.setItem(KEY, '1');
        else localStorage.removeItem(KEY);
      } catch {
        // sem armazenamento (aba anônima, bloqueio): vale só para esta visita
      }
      return next;
    });
  }, []);
  return [closed, toggle];
}
