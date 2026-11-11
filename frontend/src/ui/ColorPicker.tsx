import { useId } from 'react'
import { PALETTE, paletteColor } from '../categories/palette'

export interface ColorPickerProps {
  label: string
  /** O `#RRGGBB` escolhido, ou `null` para "sem cor". */
  value: string | null
  onChange: (value: string | null) => void
  disabled?: boolean
}

/**
 * Escolha de cor da paleta fixa, como um grupo de botões de rádio nativos (as setas e o Tab já funcionam) com a cor à vista e o nome para
 * o leitor de tela. "Sem cor" é uma opção. Uma cor que já estava gravada e não é da paleta aparece como "Cor atual", marcada, até a pessoa
 * escolher outra: abrir e salvar sem mexer nunca troca a cor em silêncio.
 */
export function ColorPicker({ label, value, onChange, disabled }: ColorPickerProps) {
  const group = useId()
  const known = paletteColor(value)
  const foreign = value !== null && known === undefined ? value : null

  const option = (key: string, name: string, color: string | null, checked: boolean, pick: () => void) => (
    <label key={key} className="color-picker__option" title={name}>
      <input type="radio" name={group} className="sr-only" checked={checked} disabled={disabled} onChange={pick} />
      <span className={`color-picker__swatch${color === null ? ' color-picker__swatch--none' : ''}`} style={color ? { background: color } : undefined} aria-hidden="true" />
      <span className="sr-only">{name}</span>
    </label>
  )

  return (
    <fieldset className="color-picker">
      <legend className="text-label">{label}</legend>
      <div className="color-picker__options">
        {option('none', 'Sem cor', null, value === null, () => onChange(null))}
        {PALETTE.map((color) => option(color.hex, color.name, color.hex, known?.hex === color.hex, () => onChange(color.hex)))}
        {foreign !== null ? option('current', 'Cor atual', foreign, true, () => onChange(foreign)) : null}
      </div>
    </fieldset>
  )
}
