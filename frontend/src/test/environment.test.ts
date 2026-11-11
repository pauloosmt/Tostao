import { describe, expect, it, vi } from 'vitest'
import { setViewportWidth } from './media'

describe('ambiente', () => {
  it('o localStorage começa vazio em cada teste (1/2)', () => {
    expect(localStorage.length).toBe(0)
    localStorage.setItem('x', '1')
  })

  it('o localStorage começa vazio em cada teste (2/2)', () => {
    expect(localStorage.length).toBe(0)
  })

  it('o matchMedia responde pela largura escolhida e avisa quem ouve', () => {
    const query = window.matchMedia('(min-width: 960px)')
    const listener = vi.fn()
    query.addEventListener('change', listener)

    expect(query.matches).toBe(true)
    setViewportWidth(400)

    expect(query.matches).toBe(false)
    expect(listener).toHaveBeenCalledTimes(1)
    expect(window.matchMedia('(max-width: 719px)').matches).toBe(true)
  })

  it('o dialog modal do jsdom abre e fecha', () => {
    const dialog = document.createElement('dialog')
    const closed = vi.fn()
    dialog.addEventListener('close', closed)

    dialog.showModal()
    expect(dialog.open).toBe(true)
    dialog.close()

    expect(dialog.open).toBe(false)
    expect(closed).toHaveBeenCalledTimes(1)
  })
})
