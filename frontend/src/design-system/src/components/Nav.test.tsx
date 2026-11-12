import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { expectNoSeriousViolations } from '../../../test/axe'
import { Nav, type NavItem } from './Nav'

const items: NavItem[] = [
  { id: 'inicio', label: 'Início', icon: 'home', href: '/' },
  { id: 'lancamentos', label: 'Lançamentos', icon: 'list', href: '/lancamentos' },
  { id: 'contas', label: 'Contas', icon: 'bank', href: '/contas' },
  { id: 'perfil', label: 'Perfil', icon: 'user', href: '/perfil' },
]

describe('Nav', () => {
  it('marca só o item atual com aria-current', () => {
    render(<Nav items={items} active="contas" />)

    expect(screen.getByRole('link', { name: 'Contas' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Início' })).not.toHaveAttribute('aria-current')
  })

  it('na barra de baixo, o botão de lançar fica no meio e chama onLaunch', async () => {
    const onLaunch = vi.fn()
    render(<Nav items={items} active="inicio" launchLabel="Novo lançamento" onLaunch={onLaunch} />)

    const labels = screen.getAllByRole('listitem').map((li) => li.textContent || li.querySelector('button')?.getAttribute('aria-label'))
    expect(labels[2]).toBe('Novo lançamento')
    await userEvent.setup().click(screen.getByRole('button', { name: 'Novo lançamento' }))
    expect(onLaunch).toHaveBeenCalledTimes(1)
  })

  it('no menu lateral, o botão de lançar vem no topo e o usuário no rodapé', async () => {
    const onUser = vi.fn()
    const onLaunch = vi.fn()
    render(<Nav items={items} active="inicio" layout="side" launchLabel="Lançar" onLaunch={onLaunch} user={{ name: 'Ana Souza', sub: 'Casa', href: '/perfil' }} onUser={onUser} />)

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /Lançar/ }))
    await user.click(screen.getByRole('link', { name: /Ana Souza/ }))
    expect(onLaunch).toHaveBeenCalledTimes(1)
    expect(onUser).toHaveBeenCalledTimes(1)
  })

  it('passa no axe nos dois layouts', async () => {
    for (const layout of ['bottom', 'side'] as const) {
      const { container, unmount } = render(<Nav items={items} active="inicio" layout={layout} launchLabel="Lançar" user={{ name: 'Ana Souza' }} />)
      await expectNoSeriousViolations(container)
      unmount()
    }
  })
})
