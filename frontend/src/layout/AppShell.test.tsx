import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import { expectNoSeriousViolations } from '../test/axe'
import { LocationProbe } from '../test/LocationProbe'
import { setViewportWidth } from '../test/media'
import { AppShell } from './AppShell'

function mount(path = '/') {
  return {
    user: userEvent.setup(),
    ...render(
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route element={<AppShell />}>
            <Route index element={<h1>Tela do início</h1>} />
            <Route path="perfil" element={<h1>Tela do perfil</h1>} />
          </Route>
        </Routes>
        <LocationProbe />
      </MemoryRouter>,
    ),
  }
}

const nav = () => screen.getByRole('navigation', { name: 'Principal' })

describe('a navegação segue o tamanho da janela', () => {
  it('abaixo de 720 px é a barra de baixo', () => {
    setViewportWidth(700)

    mount()

    expect(nav()).toHaveClass('tx-nav--bottom')
  })

  it('a partir de 960 px é o menu lateral', () => {
    setViewportWidth(960)

    mount()

    expect(nav()).toHaveClass('tx-nav--side')
  })

  it('entre os dois, ainda a barra de baixo (o lateral só cabe a partir de 960 px)', () => {
    setViewportWidth(959)

    mount()

    expect(nav()).toHaveClass('tx-nav--bottom')
  })

  it('troca sozinha quando a janela muda de tamanho', () => {
    setViewportWidth(500)
    mount()
    expect(nav()).toHaveClass('tx-nav--bottom')

    act(() => setViewportWidth(1280))
    expect(nav()).toHaveClass('tx-nav--side')

    act(() => setViewportWidth(500))
    expect(nav()).toHaveClass('tx-nav--bottom')
  })

  it('o menu lateral vem antes do conteúdo e a barra de baixo, depois', () => {
    setViewportWidth(1280)
    const wide = mount()
    expect(nav().compareDocumentPosition(screen.getByRole('main')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    wide.unmount()

    setViewportWidth(500)
    mount()
    expect(nav().compareDocumentPosition(screen.getByRole('main')) & Node.DOCUMENT_POSITION_PRECEDING).toBeTruthy()
  })
})

describe('os itens', () => {
  it('Início e Perfil, com o endereço certo, e o atual marcado', () => {
    mount('/')

    expect(screen.getByRole('link', { name: 'Início' })).toHaveAttribute('href', '/')
    expect(screen.getByRole('link', { name: 'Perfil' })).toHaveAttribute('href', '/perfil')
    expect(screen.getByRole('link', { name: 'Início' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Perfil' })).not.toHaveAttribute('aria-current')
    expect(screen.getByRole('heading', { name: 'Tela do início' })).toBeInTheDocument()
  })

  it('abrindo direto no perfil, é ele o marcado', () => {
    mount('/perfil')

    expect(screen.getByRole('link', { name: 'Perfil' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Início' })).not.toHaveAttribute('aria-current')
  })

  it('tocar em um item navega sem recarregar e passa a marcá-lo, nos dois layouts', async () => {
    for (const width of [500, 1280]) {
      setViewportWidth(width)
      const { user, unmount } = mount('/')

      await user.click(screen.getByRole('link', { name: 'Perfil' }))
      expect(screen.getByTestId('location')).toHaveTextContent('/perfil')
      expect(screen.getByRole('heading', { name: 'Tela do perfil' })).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Perfil' })).toHaveAttribute('aria-current', 'page')

      await user.click(screen.getByRole('link', { name: 'Início' }))
      expect(screen.getByTestId('location')).toHaveTextContent('/')
      expect(screen.getByRole('link', { name: 'Início' })).toHaveAttribute('aria-current', 'page')
      unmount()
    }
  })

  it('ao trocar de tela o foco vai para o conteúdo, não fica perdido no menu; ao abrir, nada rouba o foco', async () => {
    setViewportWidth(500)
    const { user } = mount('/')
    expect(screen.getByRole('main')).not.toHaveFocus()

    await user.click(screen.getByRole('link', { name: 'Perfil' }))

    expect(screen.getByRole('main')).toHaveFocus()
  })

  it('funciona pelo teclado', async () => {
    setViewportWidth(500)
    const { user } = mount('/')

    await user.tab()
    expect(screen.getByRole('link', { name: 'Início' })).toHaveFocus()
    await user.tab()
    expect(screen.getByRole('link', { name: 'Contas' })).toHaveFocus()
    await user.tab()
    expect(screen.getByRole('link', { name: 'Perfil' })).toHaveFocus()
    await user.keyboard('{Enter}')

    expect(screen.getByRole('heading', { name: 'Tela do perfil' })).toBeInTheDocument()
  })
})

describe('acessibilidade', () => {
  it('passa no axe nos dois layouts, com um único main e a navegação nomeada', async () => {
    for (const width of [500, 1280]) {
      setViewportWidth(width)
      const { container, unmount } = mount('/')

      expect(screen.getAllByRole('main')).toHaveLength(1)
      await expectNoSeriousViolations(container)
      unmount()
    }
  })
})
