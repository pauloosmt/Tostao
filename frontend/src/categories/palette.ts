// As cores que uma categoria pode ter. Fixas de propósito (sem seletor livre de cor): cada uma tem nome em português e foi escolhida para o
// ponto de cor se ver bem sobre o fundo claro e o escuro. O valor é o `#RRGGBB` que a API aceita.

export interface PaletteColor {
  /** `#RRGGBB`, em maiúsculas. */
  hex: string
  /** O nome que o leitor de tela anuncia. */
  name: string
}

export const PALETTE: readonly PaletteColor[] = [
  { hex: '#1A9E5C', name: 'Verde' },
  { hex: '#2F6FDE', name: 'Azul' },
  { hex: '#D64545', name: 'Vermelho' },
  { hex: '#E8822A', name: 'Laranja' },
  { hex: '#E0B100', name: 'Amarelo' },
  { hex: '#8A55D6', name: 'Roxo' },
  { hex: '#D9488B', name: 'Rosa' },
  { hex: '#139DA8', name: 'Turquesa' },
  { hex: '#8B5E3C', name: 'Marrom' },
  { hex: '#7A8685', name: 'Cinza' },
]

/** A cor da paleta com este valor (sem diferenciar maiúsculas), ou `undefined` se ele não é da paleta. */
export function paletteColor(hex: string | null | undefined): PaletteColor | undefined {
  if (!hex) return undefined
  const wanted = hex.toUpperCase()
  return PALETTE.find((color) => color.hex === wanted)
}
