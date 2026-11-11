import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { PALETTE, paletteColor } from '../categories/palette'
import { expectNoSeriousViolations } from '../test/axe'
import { ColorPicker } from './ColorPicker'

function Harness({ initial = null as string | null, onChange = () => {} }: { initial?: string | null; onChange?: (value: string | null) => void }) {
  const [value, setValue] = useState<string | null>(initial)
  return (
    <ColorPicker
      label="Cor"
      value={value}
      onChange={(next) => {
        setValue(next)
        onChange(next)
      }}
    />
  )
}
const radio = (name: string) => screen.getByRole('radio', { name })

describe('a paleta', () => {
  it('tem dez cores com nome e valor #RRGGBB, sem repetir', () => {
    expect(PALETTE).toHaveLength(10)
    for (const color of PALETTE) expect(color.hex).toMatch(/^#[0-9A-F]{6}$/)
    expect(new Set(PALETTE.map((c) => c.hex)).size).toBe(10)
    expect(new Set(PALETTE.map((c) => c.name)).size).toBe(10)
  })

  it('reconhece uma cor da paleta sem diferenciar maiúsculas, e não reconhece o resto', () => {
    expect(paletteColor('#1a9e5c')?.name).toBe('Verde')
    expect(paletteColor('#1A9E5C')?.name).toBe('Verde')
    expect(paletteColor('#123456')).toBeUndefined()
    expect(paletteColor(null)).toBeUndefined()
    expect(paletteColor('')).toBeUndefined()
  })
})

describe('ColorPicker', () => {
  it('é um grupo nomeado com "Sem cor" e as dez cores, cada uma um rádio com nome acessível', () => {
    render(<Harness />)

    expect(screen.getByRole('group', { name: 'Cor' })).toBeInTheDocument()
    expect(screen.getAllByRole('radio')).toHaveLength(11)
    for (const name of ['Sem cor', ...PALETTE.map((c) => c.name)]) expect(radio(name)).toBeInTheDocument()
  })

  it('sem valor, "Sem cor" está marcada', () => {
    render(<Harness initial={null} />)

    expect(radio('Sem cor')).toBeChecked()
    expect(radio('Verde')).not.toBeChecked()
  })

  it('com um valor da paleta (em qualquer caixa), é ele o marcado', () => {
    render(<Harness initial="#2f6fde" />)

    expect(radio('Azul')).toBeChecked()
    expect(radio('Sem cor')).not.toBeChecked()
  })

  it('escolher uma cor entrega o #RRGGBB; escolher "Sem cor" entrega null', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<Harness onChange={onChange} />)

    await user.click(radio('Roxo'))
    expect(onChange).toHaveBeenLastCalledWith('#8A55D6')
    expect(radio('Roxo')).toBeChecked()

    await user.click(radio('Sem cor'))
    expect(onChange).toHaveBeenLastCalledWith(null)
    expect(radio('Sem cor')).toBeChecked()
  })

  it('as setas mudam a escolha e o Tab entra e sai do grupo (rádios nativos)', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<Harness onChange={onChange} />)

    await user.tab()
    expect(radio('Sem cor')).toHaveFocus()
    await user.keyboard('{ArrowRight}')

    expect(onChange).toHaveBeenLastCalledWith(PALETTE[0].hex)
    expect(radio('Verde')).toBeChecked()
  })

  it('uma cor gravada fora da paleta aparece como "Cor atual", marcada, e é preservada até a pessoa escolher outra', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<Harness initial="#123456" onChange={onChange} />)

    expect(radio('Cor atual')).toBeChecked()
    expect(radio('Sem cor')).not.toBeChecked()
    expect(onChange).not.toHaveBeenCalled()

    await user.click(radio('Verde'))
    expect(radio('Verde')).toBeChecked()
    expect(screen.queryByRole('radio', { name: 'Cor atual' })).toBeNull()
  })

  it('desabilitado, não muda', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<ColorPicker label="Cor" value={null} onChange={onChange} disabled />)

    await user.click(radio('Verde'))

    expect(onChange).not.toHaveBeenCalled()
  })

  it('passa no axe', async () => {
    const { container } = render(<Harness initial="#123456" />)

    await expectNoSeriousViolations(container)
  })
})
