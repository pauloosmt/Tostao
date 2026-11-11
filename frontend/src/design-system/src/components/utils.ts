export type ClassValue = string | false | null | undefined;

export function cx(...parts: ClassValue[]): string {
  return parts.filter(Boolean).join(' ');
}

/** Formata centavos inteiros como `R$ 1.234,56` (espaço não separável). O sinal é responsabilidade de quem chama. */
export function formatBRL(cents: number): string {
  const n = Math.abs(Math.round(cents || 0));
  const reais = Math.floor(n / 100);
  const c = n % 100;
  return 'R$\u00a0' + String(reais).replace(/\B(?=(\d{3})+(?!\d))/g, '.') + ',' + (c < 10 ? '0' : '') + c;
}
