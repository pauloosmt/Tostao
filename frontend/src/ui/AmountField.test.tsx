import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { AmountField, nextAmountText } from './AmountField'

function Harness({ initial = '' }: { initial?: string }) {
  const [value, setValue] = useState(initial)
  return <AmountField label="Valor" value={value} onChange={setValue} />
}

describe('nextAmountText (centavos entrando pela direita, como nos bancos)', () => {
  it('cada dígito entra pela direita e as letras e símbolos não mudam nada', () => {
    expect(nextAmountText('1')).toBe('0,01')
    expect(nextAmountText('0,012')).toBe('0,12')
    expect(nextAmountText('0,125')).toBe('1,25')
    expect(nextAmountText('1,25a', '1,25')).toBe('1,25')
    expect(nextAmountText('abc')).toBe('')
    expect(nextAmountText('123456789', '')).toBe('1.234.567,89')
  })

  it('apagar tira o último dígito e zerar volta ao vazio; "0" digitado no vazio vale 0,00', () => {
    expect(nextAmountText('1,2', '1,25')).toBe('0,12')
    expect(nextAmountText('0,0', '0,05')).toBe('')
    expect(nextAmountText('0', '')).toBe('0,00')
  })

  it('passar do limite da API descarta a digitação', () => {
    expect(nextAmountText('10.000.000.000,001', '10.000.000.000,00')).toBeNull()
    expect(nextAmountText('1.000.000.000.000', '')).toBe('10.000.000.000,00')
  })
})

describe('AmountField', () => {
  it('digitando 1250 aparece 12,50 e letras são ignoradas sem aviso nenhum', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    const field = screen.getByLabelText('Valor')

    await user.type(field, '1a2x5,0')

    expect(field).toHaveValue('12,50')
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.queryByText(/Informe um valor/)).toBeNull()
    await user.type(field, '{Backspace}')
    expect(field).toHaveValue('1,25')
  })
})
