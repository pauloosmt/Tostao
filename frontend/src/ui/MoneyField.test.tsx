import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { REAIS_MESSAGES } from '../money/reais'
import { expectNoSeriousViolations } from '../test/axe'
import { MoneyField } from './MoneyField'

function Harness({ initial = '', error, hint }: { initial?: string; error?: string; hint?: string }) {
  const [value, setValue] = useState(initial)
  return (
    <>
      <MoneyField label="Saldo inicial" value={value} onChange={setValue} error={error} hint={hint} />
      <button type="button">outro lugar</button>
    </>
  )
}
const field = () => screen.getByLabelText('Saldo inicial')

describe('MoneyField', () => {
  it('é um campo de texto decimal com o prefixo R$, sem preenchimento automático', () => {
    render(<Harness />)

    expect(field()).toHaveAttribute('type', 'text')
    expect(field()).toHaveAttribute('inputmode', 'decimal')
    expect(field()).toHaveAttribute('autocomplete', 'off')
    expect(screen.getByText('R$')).toHaveAttribute('aria-hidden', 'true')
  })

  it('ao sair, um valor válido é reescrito no formato do aplicativo', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.type(field(), '1234,5')
    expect(field()).toHaveValue('1234,5')
    await user.click(screen.getByRole('button', { name: 'outro lugar' }))

    expect(field()).toHaveValue('1.234,50')
    expect(field()).toBeValid()
  })

  it.each([
    ['50', '50,00'],
    ['R$ 12,5', '12,50'],
    ['-0,5', '-0,50'],
    ['−50', '-50,00'],
  ])('%s vira %s ao sair', async (typed, shown) => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.type(field(), typed)
    await user.tab()

    expect(field()).toHaveValue(shown)
  })

  it.each([
    ['abc', REAIS_MESSAGES.format],
    ['12.5', REAIS_MESSAGES.comma],
    ['1,234', REAIS_MESSAGES.decimals],
    ['10.000.000.000,01', REAIS_MESSAGES.max],
  ])('ao sair com %s, mostra a mensagem no campo (e não reescreve o texto)', async (typed, message) => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.type(field(), typed)
    await user.tab()

    expect(field()).toHaveValue(typed)
    expect(field()).toBeInvalid()
    expect(field()).toHaveAccessibleDescription(message)
  })

  it('não reclama enquanto a pessoa digita, só depois de sair; e o erro some quando o valor passa a valer', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.type(field(), 'abc')
    expect(field()).toBeValid()
    await user.tab()
    expect(field()).toBeInvalid()

    await user.clear(field())
    await user.type(field(), '10')
    expect(field()).toBeValid()
  })

  it('vazio não é erro do campo (quem valida o envio é a tela)', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.click(field())
    await user.tab()

    expect(field()).toBeValid()
    expect(field()).toHaveValue('')
  })

  it('o erro que a tela passa tem prioridade sobre o do próprio campo, e a dica aparece sem erro', async () => {
    const user = userEvent.setup()
    const { rerender } = render(<Harness hint="Pode ser negativo." />)
    expect(field()).toHaveAccessibleDescription('Pode ser negativo.')

    await user.type(field(), 'abc')
    await user.tab()
    expect(field()).toHaveAccessibleDescription(REAIS_MESSAGES.format)

    rerender(<Harness hint="Pode ser negativo." error="Informe o saldo inicial." />)
    expect(field()).toHaveAccessibleDescription('Informe o saldo inicial.')
  })

  it('passa no axe, com e sem erro', async () => {
    const { container, rerender } = render(<Harness />)
    await expectNoSeriousViolations(container)

    rerender(<Harness error="Informe o saldo inicial." />)
    await expectNoSeriousViolations(container)
  })
})
