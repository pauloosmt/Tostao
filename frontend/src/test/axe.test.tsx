import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { Button, Field } from '../design-system/src'
import { expectNoSeriousViolations } from './axe'
import { renderWithUser } from './render'

describe('axe', () => {
  it('pega uma imagem sem texto alternativo', async () => {
    const { container } = render(<img src="a.png" />)

    await expect(expectNoSeriousViolations(container)).rejects.toThrow(/image-alt/)
  })

  it('aceita o design system bem usado', async () => {
    const { container } = render(
      <main>
        <h1>Entrar</h1>
        <Field label="E-mail" type="email" />
        <Button>Entrar</Button>
      </main>,
    )

    await expectNoSeriousViolations(container)
  })
})

describe('design system no Vitest', () => {
  it('renderiza e responde ao teclado', async () => {
    const onClick = vi.fn()
    const { user } = renderWithUser(<Button onClick={onClick}>Salvar</Button>)

    await user.tab()
    expect(screen.getByRole('button', { name: 'Salvar' })).toHaveFocus()
    await user.keyboard('{Enter}')

    expect(onClick).toHaveBeenCalledTimes(1)
  })
})
