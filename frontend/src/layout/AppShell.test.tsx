import { act, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Role } from '../api/types'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, ok } from '../test/fakeApi'
import { makeMe } from '../test/fixtures'
import { setViewportWidth } from '../test/media'
import { homeRoutes } from '../test/homeRoutes'
import { renderApp } from '../test/renderApp'

async function open(width: number, role: Role = 'OWNER', path = '/perfil') {
  setViewportWidth(width)
  fakeApi({ 'GET /api/v1/me': ok(makeMe(role)), ...homeRoutes() })
  const view = renderApp([path])
  await screen.findByRole('navigation', { name: 'Principal' })
  return view
}

const nav = () => screen.getByRole('navigation', { name: 'Principal' })
const location = () => screen.getByTestId('location').textContent

describe('a navegação', () => {
  it('no celular é a barra de baixo, com o botão de lançar no meio e o Perfil como item', async () => {
    await open(500)

    expect(nav()).toHaveClass('tx-nav--bottom')
    expect(within(nav()).getAllByRole('link').map((link) => link.textContent)).toEqual(['Início', 'Lançamentos', 'Orçamentos', 'Perfil'])
    expect(screen.getByRole('button', { name: 'Novo lançamento' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Perfil' })).toHaveAttribute('aria-current', 'page')
  })

  it('no desktop é o menu lateral, com Categorias e o usuário no rodapé levando ao Perfil', async () => {
    const { user } = await open(1280, 'OWNER', '/')
    await screen.findByRole('heading', { level: 1, name: 'Olá, Ana' })

    expect(nav()).toHaveClass('tx-nav--side')
    expect(screen.getByRole('link', { name: 'Categorias' })).toHaveAttribute('href', '/categorias')
    await user.click(screen.getByRole('link', { name: /Ana Maria/ }))

    expect(location()).toBe('/perfil')
  })

  it('troca de formato quando a janela muda de tamanho', async () => {
    await open(500)

    act(() => setViewportWidth(1280))

    expect(nav()).toHaveClass('tx-nav--side')
  })

  it('o leitor não tem o botão de lançar', async () => {
    await open(500, 'VIEWER')

    expect(screen.queryByRole('button', { name: 'Novo lançamento' })).not.toBeInTheDocument()
  })

  it('ao trocar de tela o foco vai para o conteúdo, e ao abrir nada rouba o foco', async () => {
    const { user } = await open(500)
    expect(screen.getByRole('main')).not.toHaveFocus()

    await user.click(screen.getByRole('link', { name: 'Início' }))

    expect(screen.getByRole('main')).toHaveFocus()
  })

  it('passa no axe nos dois formatos', async () => {
    for (const width of [500, 1280]) {
      const { container, unmount } = await open(width)
      expect(screen.getAllByRole('main')).toHaveLength(1)
      await expectNoSeriousViolations(container)
      unmount()
    }
  })
})
