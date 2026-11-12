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

  it('passa no axe', async () => {
    const { container } = render(<Controlled />)

    await expectNoSeriousViolations(container)
  })
})
