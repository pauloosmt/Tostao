import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { expectNoSeriousViolations } from '../../../test/axe'
import { Segmented } from './Segmented'

const options = [
  { value: 'system', label: 'Sistema' },
  { value: 'light', label: 'Claro' },
  { value: 'dark', label: 'Escuro' },
]
const pill = (container: HTMLElement) => container.querySelector('.tx-seg__pill') as HTMLElement
const group = (container: HTMLElement) => container.querySelector('.tx-seg') as HTMLElement

function Controlled() {
  const [value, setValue] = useState('system')
  return <Segmented label="Tema" value={value} options={options} onChange={setValue} />
}

describe('Segmented', () => {
  it('troca a opção escolhida e o estado vai em aria-checked', async () => {
    const user = userEvent.setup()
    render(<Controlled />)

    expect(screen.getByRole('radio', { name: 'Sistema' })).toBeChecked()
    await user.click(screen.getByRole('radio', { name: 'Escuro' }))

    expect(screen.getByRole('radio', { name: 'Escuro' })).toBeChecked()
    expect(screen.getByRole('radio', { name: 'Sistema' })).not.toBeChecked()
  })

  it('a pílula desliza até a opção escolhida (sempre o mesmo elemento), é decoração e some sem opção escolhida', async () => {
    const user = userEvent.setup()
    const { container, rerender } = render(<Controlled />)
    const first = pill(container)
    expect(group(container).style.getPropertyValue('--tx-index')).toBe('0')
    expect(group(container).style.getPropertyValue('--tx-count')).toBe('3')
    expect(first).toHaveAttribute('aria-hidden', 'true')

    await user.click(screen.getByRole('radio', { name: 'Escuro' }))
    expect(group(container).style.getPropertyValue('--tx-index')).toBe('2')
    expect(pill(container)).toBe(first)

    rerender(<Segmented label="Tema" value="nenhum" options={options} />)
    expect(pill(container)).toHaveClass('is-hidden')
  })

  it('passa no axe', async () => {
    const { container } = render(<Controlled />)

    await expectNoSeriousViolations(container)
  })
})
