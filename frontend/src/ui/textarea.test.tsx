import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { expectNoSeriousViolations } from '../test/axe'
import { TextAreaField } from './TextAreaField'

function Controlled({ max, error, hint }: { max?: number; error?: string; hint?: string }) {
  const [value, setValue] = useState('')
  return <TextAreaField label="Observações" value={value} onChange={(event) => setValue(event.target.value)} max={max} error={error} hint={hint} />
}

describe('TextAreaField', () => {
  it('é uma área de texto com o rótulo ligado', () => {
    render(<Controlled />)

    const field = screen.getByLabelText('Observações')
    expect(field).toBeInstanceOf(HTMLTextAreaElement)
    expect(field).toHaveValue('')
  })

  it('digitar muda o valor controlado, inclusive com várias linhas', async () => {
    const user = userEvent.setup()
    render(<Controlled />)

    await user.type(screen.getByLabelText('Observações'), 'linha 1{Enter}linha 2')

    expect(screen.getByLabelText('Observações')).toHaveValue('linha 1\nlinha 2')
  })

  it('o contador acompanha o tamanho e fica ligado ao campo', async () => {
    const user = userEvent.setup()
    render(<Controlled max={1000} />)
    expect(screen.getByText('0/1000')).toBeInTheDocument()

    await user.type(screen.getByLabelText('Observações'), 'abc')

    expect(screen.getByText('3/1000')).toBeInTheDocument()
    expect(screen.getByLabelText('Observações')).toHaveAccessibleDescription('3/1000')
  })

  it('passar do limite não corta o texto: marca o contador (quem valida é o envio)', async () => {
    const user = userEvent.setup()
    render(<Controlled max={5} />)

    await user.type(screen.getByLabelText('Observações'), 'abcdefg')

    expect(screen.getByLabelText('Observações')).toHaveValue('abcdefg')
    expect(screen.getByText('7/5')).toHaveClass('text-field-count--over')
  })

  it('a dica aparece sem erro; o erro a substitui, marca o campo inválido e fica ligado a ele', async () => {
    const { rerender, container } = render(<TextAreaField label="Observações" value="" onChange={() => {}} hint="Opcional." max={10} />)
    expect(screen.getByLabelText('Observações')).toHaveAccessibleDescription('Opcional. 0/10')
    expect(screen.getByLabelText('Observações')).toBeValid()

    rerender(<TextAreaField label="Observações" value="" onChange={() => {}} hint="Opcional." error="Use no máximo 1.000 caracteres." max={10} />)

    expect(screen.getByLabelText('Observações')).toBeInvalid()
    expect(screen.getByLabelText('Observações')).toHaveAccessibleDescription('Use no máximo 1.000 caracteres. 0/10')
    expect(screen.queryByText('Opcional.')).toBeNull()
    await expectNoSeriousViolations(container)
  })

  it('desabilitada, não aceita digitação', async () => {
    const user = userEvent.setup()
    render(<TextAreaField label="Observações" value="fixo" onChange={() => {}} disabled />)

    await user.type(screen.getByLabelText('Observações'), 'x')

    expect(screen.getByLabelText('Observações')).toBeDisabled()
    expect(screen.getByLabelText('Observações')).toHaveValue('fixo')
  })

  it('funciona pelo teclado (Tab chega no campo) e passa no axe', async () => {
    const user = userEvent.setup()
    const { container } = render(<Controlled max={1000} />)

    await user.tab()

    expect(screen.getByLabelText('Observações')).toHaveFocus()
    await expectNoSeriousViolations(container)
  })
})
