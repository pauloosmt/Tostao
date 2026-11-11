import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ThemeProvider } from './ThemeContext'
import { applyStoredTheme, applyTheme, readStoredTheme, useTheme } from './theme'

function Picker() {
  const { choice, setChoice } = useTheme()
  return (
    <div>
      <p data-testid="choice">{choice}</p>
      {(['system', 'light', 'dark'] as const).map((option) => (
        <button key={option} type="button" onClick={() => setChoice(option)}>
          {option}
        </button>
      ))}
    </div>
  )
}

const mount = () =>
  render(
    <ThemeProvider>
      <Picker />
    </ThemeProvider>,
  )
const theme = () => document.documentElement.getAttribute('data-theme')

describe('a escolha guardada', () => {
  it('sem nada guardado, segue o sistema: nenhum data-theme', () => {
    expect(readStoredTheme()).toBe('system')

    mount()

    expect(screen.getByTestId('choice')).toHaveTextContent('system')
    expect(theme()).toBeNull()
  })

  it('claro e escuro guardados são lidos e aplicados', () => {
    localStorage.setItem('tostao.theme', 'dark')
    expect(readStoredTheme()).toBe('dark')

    mount()

    expect(screen.getByTestId('choice')).toHaveTextContent('dark')
    expect(theme()).toBe('dark')
  })

  it('um valor guardado que não é um tema vale como "sistema"', () => {
    for (const junk of ['', 'azul', '"dark"', 'DARK', '{}']) {
      localStorage.setItem('tostao.theme', junk)
      expect(readStoredTheme(), junk).toBe('system')
    }
  })

  it('com o armazenamento bloqueado, segue o sistema sem lançar', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('bloqueado', 'SecurityError')
    })

    expect(readStoredTheme()).toBe('system')
  })
})

describe('antes da primeira pintura (main.tsx)', () => {
  it('applyStoredTheme aplica a escolha guardada ao <html> sem precisar de React', () => {
    localStorage.setItem('tostao.theme', 'light')

    expect(applyStoredTheme()).toBe('light')

    expect(theme()).toBe('light')
  })

  it('applyTheme("system") remove o atributo', () => {
    applyTheme('dark')
    expect(theme()).toBe('dark')

    applyTheme('system')

    expect(theme()).toBeNull()
  })
})

describe('escolher', () => {
  it('aplica ao <html> e lembra a escolha neste aparelho', async () => {
    const user = userEvent.setup()
    mount()

    await user.click(screen.getByRole('button', { name: 'dark' }))
    expect(theme()).toBe('dark')
    expect(localStorage.getItem('tostao.theme')).toBe('dark')

    await user.click(screen.getByRole('button', { name: 'light' }))
    expect(theme()).toBe('light')
    expect(localStorage.getItem('tostao.theme')).toBe('light')

    await user.click(screen.getByRole('button', { name: 'system' }))
    expect(theme()).toBeNull()
    expect(localStorage.getItem('tostao.theme')).toBe('system')
  })

  it('a escolha sobrevive à recarga', async () => {
    const user = userEvent.setup()
    const first = mount()
    await user.click(screen.getByRole('button', { name: 'dark' }))
    first.unmount()
    document.documentElement.removeAttribute('data-theme')

    mount()

    expect(screen.getByTestId('choice')).toHaveTextContent('dark')
    expect(theme()).toBe('dark')
  })

  it('com o armazenamento cheio, o tema muda nesta visita e nada lança', async () => {
    const user = userEvent.setup()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('cheio', 'QuotaExceededError')
    })
    mount()

    await user.click(screen.getByRole('button', { name: 'dark' }))

    expect(theme()).toBe('dark')
  })
})

describe('useTheme', () => {
  it('fora do ThemeProvider é um erro de uso, dito com clareza', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})

    expect(() => render(<Picker />)).toThrow('useTheme precisa estar dentro de um ThemeProvider')
  })
})
