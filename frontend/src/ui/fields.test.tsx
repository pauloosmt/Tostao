import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { expectNoSeriousViolations } from '../test/axe'
import { CheckboxField } from './CheckboxField'
import { SelectField } from './SelectField'

const options = [
  { value: 'CHECKING', label: 'Conta corrente' },
  { value: 'CREDIT_CARD', label: 'Cartão de crédito' },
]

function ControlledSelect({ error }: { error?: string }) {
  const [value, setValue] = useState('')
  return (
    <SelectField label="Tipo" options={options} placeholder="Escolha o tipo" value={value} onChange={(e) => setValue(e.target.value)} error={error} />
  )
}

describe('SelectField', () => {
  it('tem o rótulo ligado, a opção vazia e as opções', () => {
    render(<ControlledSelect />)

    const select = screen.getByLabelText('Tipo')
    expect(select).toBeInstanceOf(HTMLSelectElement)
    expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual(['Escolha o tipo', 'Conta corrente', 'Cartão de crédito'])
    expect(select).toHaveValue('')
  })

  it('sem placeholder não há opção vazia', () => {
    render(<SelectField label="Tipo" options={options} defaultValue="CHECKING" />)

    expect(screen.getAllByRole('option')).toHaveLength(2)
    expect(screen.getByLabelText('Tipo')).toHaveValue('CHECKING')
  })

  it('escolher uma opção (pelo teclado ou pelo toque) muda o valor controlado', async () => {
    const user = userEvent.setup()
    render(<ControlledSelect />)

    await user.selectOptions(screen.getByLabelText('Tipo'), 'CREDIT_CARD')

    expect(screen.getByLabelText('Tipo')).toHaveValue('CREDIT_CARD')
  })

  it('o erro substitui a dica, marca o campo como inválido e fica ligado a ele', async () => {
    const { container, rerender } = render(<SelectField label="Tipo" options={options} hint="Não muda depois." />)
    expect(screen.getByLabelText('Tipo')).toHaveAccessibleDescription('Não muda depois.')
    expect(screen.getByLabelText('Tipo')).toBeValid()

    rerender(<SelectField label="Tipo" options={options} hint="Não muda depois." error="Escolha o tipo da conta." />)

    expect(screen.getByLabelText('Tipo')).toBeInvalid()
    expect(screen.getByLabelText('Tipo')).toHaveAccessibleDescription('Escolha o tipo da conta.')
    expect(screen.queryByText('Não muda depois.')).toBeNull()
    await expectNoSeriousViolations(container)
  })

  it('desabilitado, não aceita mudança e é marcado como tal', async () => {
    const user = userEvent.setup()
    render(<SelectField label="Tipo" options={options} defaultValue="CHECKING" disabled />)

    await user.selectOptions(screen.getByLabelText('Tipo'), 'CREDIT_CARD').catch(() => undefined)

    expect(screen.getByLabelText('Tipo')).toBeDisabled()
    expect(screen.getByLabelText('Tipo')).toHaveValue('CHECKING')
  })
})

describe('CheckboxField', () => {
  it('o rótulo é clicável e liga e desliga a caixa', async () => {
    const user = userEvent.setup()
    function Harness() {
      const [on, setOn] = useState(false)
      return <CheckboxField label="Mostrar arquivadas" checked={on} onChange={(e) => setOn(e.target.checked)} />
    }
    render(<Harness />)
    const box = screen.getByRole('checkbox', { name: 'Mostrar arquivadas' })
    expect(box).not.toBeChecked()

    await user.click(screen.getByText('Mostrar arquivadas'))
    expect(box).toBeChecked()
    await user.click(box)
    expect(box).not.toBeChecked()
  })

  it('funciona pelo teclado (Tab e Espaço) e passa no axe', async () => {
    const user = userEvent.setup()
    const { container } = render(<CheckboxField label="Mostrar arquivadas" defaultChecked={false} />)

    await user.tab()
    expect(screen.getByRole('checkbox')).toHaveFocus()
    await user.keyboard(' ')

    expect(screen.getByRole('checkbox')).toBeChecked()
    await expectNoSeriousViolations(container)
  })

  it('desabilitada, não muda', async () => {
    const user = userEvent.setup()
    render(<CheckboxField label="Mostrar arquivadas" disabled />)

    await user.click(screen.getByRole('checkbox'))

    expect(screen.getByRole('checkbox')).not.toBeChecked()
  })
})
