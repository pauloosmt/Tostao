import { act, renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { useWalletClosed } from './useWalletClosed'

const KEY = 'tostao:carteira-fechada'

describe('useWalletClosed', () => {
  it('começa aberta; fechar guarda "1", abrir apaga; um novo uso lê o que ficou', () => {
    const first = renderHook(() => useWalletClosed())
    expect(first.result.current[0]).toBe(false)

    act(() => first.result.current[1]())
    expect(first.result.current[0]).toBe(true)
    expect(localStorage.getItem(KEY)).toBe('1')
    first.unmount()

    const second = renderHook(() => useWalletClosed())
    expect(second.result.current[0]).toBe(true)
    act(() => second.result.current[1]())
    expect(second.result.current[0]).toBe(false)
    expect(localStorage.getItem(KEY)).toBeNull()
  })

  it('sem armazenamento (bloqueado) a carteira abre e fecha só nesta visita, sem quebrar', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('bloqueado')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('bloqueado')
    })
    const { result } = renderHook(() => useWalletClosed())
    expect(result.current[0]).toBe(false)

    act(() => result.current[1]())

    expect(result.current[0]).toBe(true)
    vi.restoreAllMocks()
  })
})
