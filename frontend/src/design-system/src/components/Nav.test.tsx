import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { expectNoSeriousViolations } from '../../../test/axe'
import { Nav, type NavItem } from './Nav'

const items: NavItem[] = [
  { id: 'inicio', label: 'Início', icon: 'home', href: '/' },
  { id: 'perfil', label: 'Perfil', icon: 'wallet', href: '/perfil' },
  { id: 'contas', label: 'Contas', icon: 'bank', href: '/contas' },
]
const pill = (container: HTMLElement) => container.querySelector('.tx-nav__pill') as HTMLElement
const track = (container: HTMLElement) => container.querySelector('.tx-nav__track') as HTMLElement

describe('Nav: o indicador que desliza', () => {
  it('fica no item atual e acompanha a mudança (o CSS anima o deslocamento)', () => {
    const { container, rerender } = render(<Nav items={items} active="inicio" />)
    expect(track(container).style.getPropertyValue('--tx-index')).toBe('0')
    expect(track(container).style.getPropertyValue('--tx-count')).toBe('3')

    rerender(<Nav items={items} active="contas" />)
    expect(track(container).style.getPropertyValue('--tx-index')).toBe('2')
    expect(pill(container)).toBe(container.querySelector('.tx-nav__pill')) // o mesmo elemento: só se move, não é recriado
  })

  it('é decoração: escondido do leitor de tela, e o estado continua em aria-current', () => {
    const { container } = render(<Nav items={items} active="perfil" />)

    expect(pill(container)).toHaveAttribute('aria-hidden', 'true')
    expect(screen.getByRole('link', { name: 'Perfil' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Início' })).not.toHaveAttribute('aria-current')
  })

  it('sem item atual, a pílula some em vez de apontar para o primeiro', () => {
    const { container } = render(<Nav items={items} active="nenhum" />)

    expect(pill(container)).toHaveClass('is-hidden')
  })

  it('vale nos dois layouts e passa no axe', async () => {
    for (const layout of ['bottom', 'side'] as const) {
      const { container, unmount } = render(<Nav items={items} active="inicio" layout={layout} />)
      expect(pill(container)).toBeInTheDocument()
      await expectNoSeriousViolations(container)
      unmount()
    }
  })
})
